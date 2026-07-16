package com.arnebiae.mctgauth.http.dto;

import com.google.gson.annotations.SerializedName;

/** POST /api/v1/register-token 响应。 */
public class TokenResponse {
	public String token;

	@SerializedName("expires_at")
	public long expiresAt;

	@SerializedName("bot_username")
	public String botUsername;
}
