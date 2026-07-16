package com.arnebiae.mctgauth.http;

/**
 * Bot 服务返回非 2xx 时抛出，携带错误码（unauthorized/already_bound/rate_limited/not_bound/not_found/tg_send_failed）。
 * errorCode 为 null 表示无法解析出结构化错误（如网络异常在别处包装）。
 */
public class ApiException extends RuntimeException {
	private final int statusCode;
	private final String errorCode;

	public ApiException(int statusCode, String errorCode, String message) {
		super(message);
		this.statusCode = statusCode;
		this.errorCode = errorCode;
	}

	public int statusCode() {
		return statusCode;
	}

	/** 结构化错误码，可能为 null。 */
	public String errorCode() {
		return errorCode;
	}
}
