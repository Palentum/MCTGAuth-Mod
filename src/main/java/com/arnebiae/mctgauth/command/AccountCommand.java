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
import java.util.UUID;

/**
 * /account register 与 /account login 命令。冻结期间由 mixin 单独放行。
 */
public final class AccountCommand {
	private AccountCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AuthManager auth) {
		dispatcher.register(
				Commands.literal("account")
						.then(Commands.literal("register").executes(ctx -> runRegister(ctx.getSource(), auth)))
						.then(Commands.literal("login").executes(ctx -> runLogin(ctx.getSource(), auth)))
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

		MinecraftServer server = source.getServer();
		String name = player.getGameProfile().name();
		auth.api().createRegisterToken(uuid, name).whenComplete((resp, ex) -> server.execute(() -> {
			ServerPlayer online = server.getPlayerList().getPlayer(uuid);
			PlayerAuthEntry current = auth.getEntry(uuid);
			if (online == null || current == null) {
				return;
			}
			if (ex != null) {
				handleRegisterError(online, msg, current, ex);
				return;
			}
			// 展示 token、机器人用户名与可点击深链。
			online.sendSystemMessage(msg.get("registerToken", "token", resp.token, "bot", safe(resp.botUsername)));
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

		MinecraftServer server = source.getServer();
		String name = player.getGameProfile().name();
		String ip = AuthManager.extractIp(player);
		auth.api().createLoginRequest(uuid, name, ip).whenComplete((resp, ex) -> server.execute(() -> {
			ServerPlayer online = server.getPlayerList().getPlayer(uuid);
			PlayerAuthEntry current = auth.getEntry(uuid);
			if (online == null || current == null) {
				return;
			}
			if (ex != null) {
				handleLoginError(online, msg, current, ex);
				return;
			}
			// 登录请求创建成功即证明已绑定，同步刷新本地状态。
			current.state = AuthState.BOUND_UNAUTHENTICATED;
			current.pendingLoginRequestId = resp.requestId;
			// 让轮询尽快开始。
			current.pollCooldownUntilTick = auth.serverTick();
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

	/** 构造可点击的 Telegram 深链组件。 */
	private static Component deepLink(AuthManager auth, String botUsername, String token) {
		String bot = safe(botUsername);
		String url = "https://t.me/" + bot + "?start=" + token;
		MutableComponent text = Component.literal(auth.messages().raw("registerLinkText"));
		return text.withStyle(Style.EMPTY.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
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
