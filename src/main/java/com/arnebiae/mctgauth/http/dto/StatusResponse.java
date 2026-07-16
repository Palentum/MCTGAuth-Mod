package com.arnebiae.mctgauth.http.dto;

/** GET/DELETE /api/v1/login-request/{id} 响应，status 取值 pending/approved/denied/expired/cancelled。 */
public class StatusResponse {
	public String status;
}
