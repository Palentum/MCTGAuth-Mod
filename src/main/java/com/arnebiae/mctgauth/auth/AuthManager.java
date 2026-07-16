package com.arnebiae.mctgauth.auth;

import com.arnebiae.mctgauth.McTgAuthMod;
import com.arnebiae.mctgauth.config.ModConfig;
import com.arnebiae.mctgauth.http.BotApiClient;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证核心：状态机、冻结管理、登录轮询、IP 会话。
 *
 * 线程模型：
 *  - entries、ipSessions 及绝大多数方法只在服务器主线程访问。
 *  - frozenUuids 是并发集合，供 netty 线程上的 mixin 只读判断“是否冻结”。
 *  - 所有 HTTP 回调必须先 server.execute(...) 跳回主线程，再重新校验玩家在线。
 */
public class AuthManager {
	/** 位置重同步周期（tick）。 */
	private static final int RESYNC_INTERVAL_TICKS = 20;
	/** 绑定查询失败后的重试间隔（tick）。 */
	private static final long BINDING_RETRY_TICKS = 100;
	/** frozenHint 节流间隔（毫秒）。 */
	private static final long HINT_THROTTLE_MILLIS = 2000;

	private final ModConfig config;
	private final BotApiClient api;
	private final Messages messages;

	// 仅主线程访问。
	private final Map<UUID, PlayerAuthEntry> entries = new HashMap<>();
	private final Map<UUID, IpSession> ipSessions = new HashMap<>();

	// netty 线程可读的冻结镜像。
	private static final Set<UUID> frozenUuids = ConcurrentHashMap.newKeySet();

	// netty 线程写、主线程消费的待拉回标记：mixin 取消越位移动包后置位。
	private static final Set<UUID> pendingResync = ConcurrentHashMap.newKeySet();

	private MinecraftServer server;
	private long serverTick;

	public AuthManager(ModConfig config, BotApiClient api, Messages messages) {
		this.config = config;
		this.api = api;
		this.messages = messages;
	}

	public void setServer(MinecraftServer server) {
		this.server = server;
	}

	public Messages messages() {
		return messages;
	}

	/** 供 mixin（netty 线程）判断玩家是否处于冻结状态。 */
	public static boolean isFrozen(UUID uuid) {
		return frozenUuids.contains(uuid);
	}

	/** 供 mixin（netty 线程）在取消越位移动包后请求主线程把客户端拉回冻结点。 */
	public static void requestResync(UUID uuid) {
		pendingResync.add(uuid);
	}

	// ============ 玩家生命周期 ============

	/** 玩家加入：冻结并启动认证流程。主线程调用。 */
	public void onJoin(ServerPlayer player) {
		UUID uuid = player.getUUID();
		PlayerAuthEntry entry = new PlayerAuthEntry();

		// 记录冻结位置。
		entry.freezeX = player.getX();
		entry.freezeY = player.getY();
		entry.freezeZ = player.getZ();
		entry.freezeDimension = player.level().dimension().identifier().toString();

		// 保存并开启无敌，避免冻结期间被伤害。
		entry.savedInvulnerable = player.isInvulnerable();
		player.setInvulnerable(true);

		// 设置踢出截止。
		entry.deadlineTick = serverTick + (long) config.kickTimeoutSeconds * 20L;

		entries.put(uuid, entry);
		frozenUuids.add(uuid);

		// IP 会话命中则直接认证。
		String ip = extractIp(player);
		if (config.ipSessionMinutes > 0) {
			IpSession session = ipSessions.get(uuid);
			if (session != null && session.matches(ip, System.currentTimeMillis())) {
				entry.state = AuthState.AUTHENTICATED;
				unfreeze(player, entry, messages.get("sessionRestored"));
				return;
			}
		}

		// 否则查询绑定关系。
		lookupBinding(player, entry);
	}

	/** 玩家离线：撤销待处理登录请求并清理条目。主线程调用。 */
	public void onDisconnect(ServerPlayer player) {
		UUID uuid = player.getUUID();
		PlayerAuthEntry entry = entries.remove(uuid);
		frozenUuids.remove(uuid);
		pendingResync.remove(uuid);
		if (entry != null && entry.pendingLoginRequestId != null) {
			// fire-and-forget：忽略结果与异常。
			api.cancelLoginRequest(entry.pendingLoginRequestId).exceptionally(ex -> null);
		}
	}

	// ============ 每 tick 驱动 ============

	/** 服务器主线程每 tick 末调用。 */
	public void onEndServerTick(MinecraftServer server) {
		this.serverTick++;
		if (entries.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, PlayerAuthEntry>> it = entries.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, PlayerAuthEntry> e = it.next();
			UUID uuid = e.getKey();
			PlayerAuthEntry entry = e.getValue();

			// 已认证的条目无需继续处理，保留仅为记录，不占用逻辑。
			if (entry.state == AuthState.AUTHENTICATED) {
				continue;
			}

			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player == null) {
				// 玩家已不在线但 DISCONNECT 尚未清理（理论少见），跳过。
				continue;
			}

			// 踢出截止检查。断开是异步处理的，这里先移除条目与冻结镜像，
			// 避免遍历期间被后续 DISCONNECT 回调并发修改。
			if (serverTick >= entry.deadlineTick) {
				it.remove();
				frozenUuids.remove(uuid);
				pendingResync.remove(uuid);
				if (entry.pendingLoginRequestId != null) {
					api.cancelLoginRequest(entry.pendingLoginRequestId).exceptionally(ex -> null);
				}
				player.connection.disconnect(messages.get("kickTimeout"));
				continue;
			}

			// 位置重同步：mixin 标记的越位拉回立即执行；周期兜底覆盖服务端位置
			// 被外力（水流、活塞、实体推挤）改变的情况。
			boolean force = pendingResync.remove(uuid);
			if (force || serverTick % RESYNC_INTERVAL_TICKS == 0) {
				resyncPosition(player, entry, force);
			}

			// 绑定查询失败后的重试。
			if (entry.bindingRetryAtTick != 0 && serverTick >= entry.bindingRetryAtTick) {
				entry.bindingRetryAtTick = 0;
				lookupBinding(player, entry);
			}

			// 轮询待处理的登录请求。
			if (entry.pendingLoginRequestId != null
					&& !entry.pollInFlight
					&& serverTick >= entry.pollCooldownUntilTick) {
				pollLoginStatus(player, entry);
			}
		}
	}

	private void resyncPosition(ServerPlayer player, PlayerAuthEntry entry, boolean force) {
		double dx = player.getX() - entry.freezeX;
		double dy = player.getY() - entry.freezeY;
		double dz = player.getZ() - entry.freezeZ;
		if (!force && dx * dx + dy * dy + dz * dz <= 1.0e-4) {
			return;
		}
		// 绝对位置 + 相对旋转（增量 0）+ 速度清零：把客户端拉回冻结点且不打断玩家视角。
		player.connection.teleport(
				new PositionMoveRotation(new Vec3(entry.freezeX, entry.freezeY, entry.freezeZ), Vec3.ZERO, 0.0F, 0.0F),
				Relative.ROTATION);
	}

	// ============ HTTP 流程 ============

	private void lookupBinding(ServerPlayer player, PlayerAuthEntry entry) {
		if (entry.bindingLookupInFlight) {
			return;
		}
		entry.bindingLookupInFlight = true;
		UUID uuid = player.getUUID();
		api.getBinding(uuid).whenComplete((resp, ex) -> server.execute(() -> {
			PlayerAuthEntry current = entries.get(uuid);
			ServerPlayer online = server.getPlayerList().getPlayer(uuid);
			if (current == null || current != entry || online == null) {
				return; // 玩家已离线或条目已更替。
			}
			entry.bindingLookupInFlight = false;
			if (ex != null) {
				// 服务不可用，提示并计划一次重试，保持冻结。
				McTgAuthMod.LOGGER.warn("查询绑定关系失败，将在 5 秒后重试：{}", uuid, ex);
				online.sendSystemMessage(messages.get("serviceUnavailable"));
				entry.bindingRetryAtTick = serverTick + BINDING_RETRY_TICKS;
				return;
			}
			if (resp.bound) {
				entry.state = AuthState.BOUND_UNAUTHENTICATED;
				online.sendSystemMessage(messages.get("needLogin"));
			} else {
				entry.state = AuthState.UNBOUND;
				online.sendSystemMessage(messages.get("needRegister"));
			}
		}));
	}

	private void pollLoginStatus(ServerPlayer player, PlayerAuthEntry entry) {
		entry.pollInFlight = true;
		entry.pollCooldownUntilTick = serverTick + config.pollIntervalTicks;
		UUID uuid = player.getUUID();
		String requestId = entry.pendingLoginRequestId;
		api.getLoginRequestStatus(requestId).whenComplete((resp, ex) -> server.execute(() -> {
			PlayerAuthEntry current = entries.get(uuid);
			ServerPlayer online = server.getPlayerList().getPlayer(uuid);
			if (current == null || current != entry || online == null) {
				return;
			}
			entry.pollInFlight = false;
			// 请求可能在轮询过程中被改动（如玩家重发），仅处理仍匹配的响应。
			if (!requestId.equals(entry.pendingLoginRequestId)) {
				return;
			}
			if (ex != null) {
				// 单次轮询失败静默重试，不打扰玩家。
				McTgAuthMod.LOGGER.debug("轮询登录状态失败：{}", requestId, ex);
				return;
			}
			switch (resp.status == null ? "" : resp.status) {
				case "approved" -> {
					entry.pendingLoginRequestId = null;
					entry.state = AuthState.AUTHENTICATED;
					unfreeze(online, entry, messages.get("loginApproved"));
				}
				case "denied" -> {
					entry.pendingLoginRequestId = null;
					online.sendSystemMessage(messages.get("loginDenied"));
				}
				case "expired" -> {
					entry.pendingLoginRequestId = null;
					online.sendSystemMessage(messages.get("loginExpired"));
				}
				case "cancelled" -> {
					entry.pendingLoginRequestId = null;
					online.sendSystemMessage(messages.get("loginCancelled"));
				}
				default -> {
					// pending：继续等待下一次轮询。
				}
			}
		}));
	}

	// ============ 解冻 ============

	/** 解冻玩家：恢复无敌、记录 IP 会话、发送欢迎消息。主线程调用。 */
	public void unfreeze(ServerPlayer player, PlayerAuthEntry entry, Component welcome) {
		UUID uuid = player.getUUID();
		frozenUuids.remove(uuid);
		pendingResync.remove(uuid);
		player.setInvulnerable(entry.savedInvulnerable);
		if (config.ipSessionMinutes > 0) {
			long expiresAt = System.currentTimeMillis() + (long) config.ipSessionMinutes * 60_000L;
			ipSessions.put(uuid, new IpSession(extractIp(player), expiresAt));
		}
		player.sendSystemMessage(welcome);
	}

	// ============ 提示节流 ============

	/**
	 * 按节流规则向玩家发送 frozenHint（每玩家每 2 秒最多一次）。可从任意线程安全调用，
	 * 内部会调度到主线程发送。
	 */
	public void sendThrottledFrozenHint(UUID uuid) {
		server.execute(() -> {
			PlayerAuthEntry entry = entries.get(uuid);
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (entry == null || player == null) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now - entry.lastHintMillis < HINT_THROTTLE_MILLIS) {
				return;
			}
			entry.lastHintMillis = now;
			player.sendSystemMessage(messages.get("frozenHint"));
		});
	}

	// ============ 命令访问 ============

	/** 取玩家条目，可能为 null（玩家未在管理范围内，理论不应发生）。 */
	public PlayerAuthEntry getEntry(UUID uuid) {
		return entries.get(uuid);
	}

	public ModConfig config() {
		return config;
	}

	public BotApiClient api() {
		return api;
	}

	public long serverTick() {
		return serverTick;
	}

	// ============ 工具 ============

	/** 从 ServerPlayer 取不含端口的 IP 字符串。 */
	public static String extractIp(ServerPlayer player) {
		String raw = player.getIpAddress(); // 形如 "/127.0.0.1:54321" 或 "127.0.0.1:54321"
		if (raw == null) {
			return "";
		}
		String ip = raw.startsWith("/") ? raw.substring(1) : raw;
		int colon = ip.lastIndexOf(':');
		// 仅当冒号后是端口（IPv4 情况）时截断；IPv6 含多个冒号，getIpAddress 通常已给纯地址。
		if (colon > 0 && ip.indexOf(':') == colon) {
			ip = ip.substring(0, colon);
		}
		return ip;
	}

	/** IP 免登录会话。 */
	private record IpSession(String ip, long expiresAtMillis) {
		boolean matches(String candidateIp, long nowMillis) {
			return nowMillis < expiresAtMillis && ip != null && ip.equals(candidateIp);
		}
	}
}
