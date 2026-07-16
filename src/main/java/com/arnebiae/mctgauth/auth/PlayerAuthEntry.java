package com.arnebiae.mctgauth.auth;

/**
 * 单个玩家的认证与冻结状态，仅在服务器主线程访问。
 */
public class PlayerAuthEntry {
	public AuthState state = AuthState.UNBOUND;

	// 冻结时记录的原始位置，用于位置重同步。
	public double freezeX;
	public double freezeY;
	public double freezeZ;
	public float freezeYaw;
	public float freezePitch;
	public String freezeDimension;

	/** 踢出截止 tick（服务器总 tick 数）。 */
	public long deadlineTick;

	/** 当前待处理的登录请求 ID，null 表示无。 */
	public String pendingLoginRequestId;

	/** 下次可轮询登录状态的 tick，用于按 pollIntervalTicks 节流。 */
	public long pollCooldownUntilTick;

	/** 登录状态轮询单并发在途标志。 */
	public boolean pollInFlight;

	/** 绑定查询在途标志，避免重复发起。 */
	public boolean bindingLookupInFlight;

	/** 绑定查询失败后的重试 tick（0 表示无待重试）。 */
	public long bindingRetryAtTick;

	/** 冻结前玩家原本的无敌状态，解冻时恢复。 */
	public boolean savedInvulnerable;

	/** 上次发送 frozenHint 的时间戳（毫秒），用于节流。 */
	public long lastHintMillis;
}
