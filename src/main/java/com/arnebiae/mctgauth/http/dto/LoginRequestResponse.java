package com.arnebiae.mctgauth.http.dto;

import com.google.gson.annotations.SerializedName;

/** POST /api/v1/login-request 响应。 */
public class LoginRequestResponse {
	@SerializedName("request_id")
	public String requestId;

	@SerializedName("expires_at")
	public long expiresAt;
}
