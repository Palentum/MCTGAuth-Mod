package com.arnebiae.mctgauth.auth;

/**
 * 玩家认证状态。
 * UNBOUND：未绑定 Telegram，需先 /account register。
 * BOUND_UNAUTHENTICATED：已绑定但本次会话未登录，需 /account login。
 * AUTHENTICATED：已登录，正常游戏。
 */
public enum AuthState {
	UNBOUND,
	BOUND_UNAUTHENTICATED,
	AUTHENTICATED
}
