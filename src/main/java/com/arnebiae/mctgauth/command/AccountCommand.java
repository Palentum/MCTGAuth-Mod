package com.arnebiae.mctgauth.command;

import com.mojang.brigadier.CommandDispatcher;
import com.arnebiae.mctgauth.McTgAuthMod;
import com.arnebiae.mctgauth.auth.AuthManager;
import com.arnebiae.mctgauth.auth.AuthState;
import com.arnebiae.mctgauth.auth.Messages;
import com.arnebiae.mctgauth.auth.PlayerAuthEntry;
import com.arnebiae.mctgauth.http.ApiException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * /account register 与 /account login 命令。冻结期间由 mixin 单独放行。
 */
public final class AccountCommand {
	/** 两次 register/login 命令之间的最小间隔（tick，1 秒），抑制宏高频刷命令。 */
	private static final long ACCOUNT_COMMAND_COOLDOWN_TICKS = 20L;

	private AccountCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AuthManager auth) {
		dispatcher.register(
				Commands.literal("account")
						.then(Commands.literal("register").executes(ctx -> runRegister(ctx.getSource(), auth)))
						.then(Commands.literal("login").executes(ctx -> runLogin(ctx.getSource(), auth)))
						.then(Commands.literal("logout").executes(ctx -> runLogout(ctx.getSource(), auth)))
		);
	}

	private static int runRegister(CommandSourceStack source, AuthManager auth) {
		ServerPlayer player = source.getPlayer();
		Messages msg = auth.messages();
		if (player == null) {
			source.sendSystemMessage(msg.get("playersOnly"));
			return 0;
		}
		UUID uuid = player.getUUID();
		PlayerAuthEntry entry = auth.getEntry(uuid);
		if (entry == null) {
			return 0;
		}
		if (entry.state == AuthState.AUTHENTICATED) {
			player.sendSystemMessage(msg.get("alreadyLoggedIn"));
			return 1;
		}
		if (entry.state == AuthState.BOUND_UNAUTHENTICATED) {
			// 已绑定，引导去登录。
			player.sendSystemMessage(msg.get("alreadyBound"));
			return 1;
		}
		// 在途去重 + 每玩家命令冷却：抑制宏高频刷 /account register 对 Bot 的放大。
		// 静默丢弃多余命令，避免向刷命令的玩家回刷提示（与 frozenHint 节流思路一致）。
		if (entry.registerInFlight || auth.serverTick() < entry.nextAccountCommandTick) {
			return 1;
		}
		entry.registerInFlight = true;
		entry.nextAccountCommandTick = auth.serverTick() + ACCOUNT_COMMAND_COOLDOWN_TICKS;

		MinecraftServer server = source.getServer();
		String name = player.getGameProfile().name();
		auth.api().createRegisterToken(uuid, name).whenComplete((resp, ex) -> server.execute(() -> {
			PlayerAuthEntry current = auth.getEntry(uuid);
			if (current != entry) {
				return; // 条目已更替（断线重连）或已移除，旧回调不得触碰新会话状态。
			}
			entry.registerInFlight = false; // HTTP 已结束，无论成败解除在途
			ServerPlayer online = server.getPlayerList().getPlayer(uuid);
			if (online == null) {
				return;
			}
			if (ex != null) {
				handleRegisterError(online, msg, entry, ex);
				return;
			}
			// 先置等待绑定：必须早于发消息/深链，避免文案构造抛异常跳过该行导致 needRegister 提示继续刷屏。
			entry.awaitingBinding = true;
			// 展示 token、机器人用户名与可点击深链。
			online.sendSystemMessage(msg.get("registerToken", "token", safe(resp.token), "bot", safe(resp.botUsername)));
			online.sendSystemMessage(deepLink(auth, resp.botUsername, resp.token));
		}));
		return 1;
	}

	private static void handleRegisterError(ServerPlayer online, Messages msg, PlayerAuthEntry entry, Throwable ex) {
		Throwable cause = unwrap(ex);
		if (cause instanceof ApiException api) {
			String code = api.errorCode();
			if ("already_bound".equals(code)) {
				entry.state = AuthState.BOUND_UNAUTHENTICATED;
				online.sendSystemMessage(msg.get("alreadyBound"));
				return;
			}
			if ("rate_limited".equals(code)) {
				online.sendSystemMessage(msg.get("rateLimited"));
				return;
			}
		}
		McTgAuthMod.LOGGER.warn("获取绑定令牌失败：{}", online.getUUID(), cause);
		online.sendSystemMessage(msg.get("serviceUnavailable"));
	}

	private static int runLogin(CommandSourceStack source, AuthManager auth) {
		ServerPlayer player = source.getPlayer();
		Messages msg = auth.messages();
		if (player == null) {
			source.sendSystemMessage(msg.get("playersOnly"));
			return 0;
		}
		UUID uuid = player.getUUID();
		PlayerAuthEntry entry = auth.getEntry(uuid);
		if (entry == null) {
			return 0;
		}
		if (entry.state == AuthState.AUTHENTICATED) {
			player.sendSystemMessage(msg.get("alreadyLoggedIn"));
			return 1;
		}
		// 不做本地未绑定拦截：绑定可能刚在 Telegram 完成而本地缓存未刷新，
		// 以 Bot 服务端为权威，未绑定时由 handleLoginError 的 not_bound 分支提示。
		if (entry.pendingLoginRequestId != null) {
			player.sendSystemMessage(msg.get("alreadyPending"));
			return 1;
		}
		// 在途去重 + 每玩家命令冷却：pendingLoginRequestId 仅在回调后写入，
		// loginInFlight 覆盖“已发起未回调”窗口，防止重复创建登录请求
		// （后到请求会覆盖 pendingLoginRequestId，令先前请求在 Bot 侧悬挂至过期）。
		// 静默丢弃多余命令，避免向刷命令的玩家回刷提示。
		if (entry.loginInFlight || auth.serverTick() < entry.nextAccountCommandTick) {
			return 1;
		}
		entry.loginInFlight = true;
		entry.nextAccountCommandTick = auth.serverTick() + ACCOUNT_COMMAND_COOLDOWN_TICKS;

		MinecraftServer server = source.getServer();
		String name = player.getGameProfile().name();
		String ip = AuthManager.extractIp(player);
		auth.api().createLoginRequest(uuid, name, ip).whenComplete((resp, ex) -> server.execute(() -> {
			PlayerAuthEntry current = auth.getEntry(uuid);
			if (current != entry) {
				// 条目已更替（断线重连）或已移除，旧回调不得触碰新会话状态；
				// 旧登录请求不再被任何会话轮询，主动撤销，避免在 Bot 侧悬挂可批准的请求。
				if (ex == null && resp.requestId != null) {
					auth.api().cancelLoginRequest(resp.requestId).exceptionally(e -> null);
				}
				return;
			}
			entry.loginInFlight = false; // HTTP 已结束，无论成败解除在途
			ServerPlayer online = server.getPlayerList().getPlayer(uuid);
			if (online == null) {
				return;
			}
			if (ex != null) {
				handleLoginError(online, msg, entry, ex);
				return;
			}
			// 登录请求创建成功即证明已绑定，同步刷新本地状态。
			entry.state = AuthState.BOUND_UNAUTHENTICATED;
			entry.pendingLoginRequestId = resp.requestId;
			// 让轮询尽快开始。
			entry.pollCooldownUntilTick = auth.serverTick();
			// 已确认绑定并进入等待批准，清除等待绑定标记；此后由 pendingLoginRequestId 负责暂停提示。
			entry.awaitingBinding = false;
			online.sendSystemMessage(msg.get("loginSent"));
		}));
		return 1;
	}

	private static void handleLoginError(ServerPlayer online, Messages msg, PlayerAuthEntry entry, Throwable ex) {
		Throwable cause = unwrap(ex);
		if (cause instanceof ApiException api) {
			String code = api.errorCode();
			if ("not_bound".equals(code)) {
				// 服务端认为未绑定，回退到未绑定状态并引导注册。
				entry.state = AuthState.UNBOUND;
				online.sendSystemMessage(msg.get("notBound"));
				return;
			}
			if ("rate_limited".equals(code)) {
				online.sendSystemMessage(msg.get("rateLimited"));
				return;
			}
		}
		McTgAuthMod.LOGGER.warn("创建登录请求失败：{}", online.getUUID(), cause);
		online.sendSystemMessage(msg.get("serviceUnavailable"));
	}

	private static int runLogout(CommandSourceStack source, AuthManager auth) {
		ServerPlayer player = source.getPlayer();
		Messages msg = auth.messages();
		if (player == null) {
			source.sendSystemMessage(msg.get("playersOnly"));
			return 0;
		}
		UUID uuid = player.getUUID();
		PlayerAuthEntry entry = auth.getEntry(uuid);
		if (entry == null) {
			return 0;
		}
		if (entry.state != AuthState.AUTHENTICATED) {
			// 未登录（未绑定或已绑定未认证）无从登出，此时玩家仍处于冻结流程中。
			player.sendSystemMessage(msg.get("notLoggedIn"));
			return 1;
		}
		auth.logout(player, entry);
		return 1;
	}

	/** 构造可点击的 Telegram 深链组件；URL 非法时退化为纯文本，避免异常中断回调。 */
	private static Component deepLink(AuthManager auth, String botUsername, String token) {
		String url = "https://t.me/" + URLEncoder.encode(safe(botUsername), StandardCharsets.UTF_8)
				+ "?start=" + URLEncoder.encode(safe(token), StandardCharsets.UTF_8);
		MutableComponent text = Component.literal(auth.messages().raw("registerLinkText"));
		try {
			return text.withStyle(Style.EMPTY.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
		} catch (IllegalArgumentException e) {
			// token 是可绑定账号的凭据，且 URISyntaxException 的消息会携带完整 URL，故不记录 URL 与异常详情。
			McTgAuthMod.LOGGER.warn("构造 Telegram 深链失败，退化为纯文本");
			return text;
		}
	}

	private static String safe(String s) {
		return s == null ? "" : s;
	}

	private static Throwable unwrap(Throwable ex) {
		if (ex != null && ex.getCause() != null && (ex instanceof java.util.concurrent.CompletionException)) {
			return ex.getCause();
		}
		return ex;
	}
}
