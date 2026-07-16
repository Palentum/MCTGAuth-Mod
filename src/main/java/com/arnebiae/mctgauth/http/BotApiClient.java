package com.arnebiae.mctgauth.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.arnebiae.mctgauth.McTgAuthMod;
import com.arnebiae.mctgauth.config.ModConfig;
import com.arnebiae.mctgauth.http.dto.ApiError;
import com.arnebiae.mctgauth.http.dto.BindingResponse;
import com.arnebiae.mctgauth.http.dto.LoginRequestResponse;
import com.arnebiae.mctgauth.http.dto.StatusResponse;
import com.arnebiae.mctgauth.http.dto.TokenResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bot 服务 HTTP 客户端。所有请求由本模组主动发起（轮询模型），
 * 携带 Bearer 令牌，异步返回 CompletableFuture。
 *
 * 线程约定：回调在 HttpClient 的线程上执行，调用方在触碰任何游戏状态前
 * 必须通过 server.execute(...) 跳回主线程，并重新校验玩家是否仍在线。
 */
public class BotApiClient {
	private static final Gson GSON = new Gson();

	private final HttpClient httpClient;
	private final String baseUrl;
	private final String token;

	public BotApiClient(ModConfig config) {
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build();
		// 去掉末尾斜杠，避免拼出双斜杠。
		String url = config.apiBaseUrl == null ? "" : config.apiBaseUrl.trim();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		this.baseUrl = url;
		this.token = config.apiToken == null ? "" : config.apiToken;
	}

	/** 关闭底层执行器（JDK 21+ HttpClient 实现 AutoCloseable）。 */
	public void shutdown() {
		try {
			httpClient.close();
		} catch (Exception e) {
			McTgAuthMod.LOGGER.warn("关闭 HTTP 客户端时出现异常", e);
		}
	}

	private HttpRequest.Builder base(String path) {
		return HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + path))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/json");
	}

	/** GET /api/v1/binding/{mc_uuid} */
	public CompletableFuture<BindingResponse> getBinding(UUID uuid) {
		HttpRequest request = base("/api/v1/binding/" + uuid).GET().build();
		return send(request, BindingResponse.class);
	}

	/** POST /api/v1/register-token */
	public CompletableFuture<TokenResponse> createRegisterToken(UUID uuid, String name) {
		JsonObject body = new JsonObject();
		body.addProperty("mc_uuid", uuid.toString());
		body.addProperty("mc_name", name);
		HttpRequest request = base("/api/v1/register-token")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		return send(request, TokenResponse.class);
	}

	/** POST /api/v1/login-request */
	public CompletableFuture<LoginRequestResponse> createLoginRequest(UUID uuid, String name, String ip) {
		JsonObject body = new JsonObject();
		body.addProperty("mc_uuid", uuid.toString());
		body.addProperty("mc_name", name);
		body.addProperty("ip", ip);
		HttpRequest request = base("/api/v1/login-request")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		return send(request, LoginRequestResponse.class);
	}

	/** GET /api/v1/login-request/{id} */
	public CompletableFuture<StatusResponse> getLoginRequestStatus(String id) {
		HttpRequest request = base("/api/v1/login-request/" + id).GET().build();
		return send(request, StatusResponse.class);
	}

	/** DELETE /api/v1/login-request/{id} */
	public CompletableFuture<StatusResponse> cancelLoginRequest(String id) {
		HttpRequest request = base("/api/v1/login-request/" + id).DELETE().build();
		return send(request, StatusResponse.class);
	}

	/** 发送请求并将响应体解析为 T；非 2xx 时以 ApiException 完成。 */
	private <T> CompletableFuture<T> send(HttpRequest request, Class<T> type) {
		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> {
					int code = response.statusCode();
					String body = response.body();
					if (code >= 200 && code < 300) {
						T parsed = GSON.fromJson(body, type);
						if (parsed == null) {
							throw new ApiException(code, null, "响应体为空");
						}
						return parsed;
					}
					throw toApiException(code, body);
				});
	}

	private ApiException toApiException(int code, String body) {
		String errorCode = null;
		String message = "HTTP " + code;
		try {
			ApiError err = GSON.fromJson(body, ApiError.class);
			if (err != null) {
				if (err.error != null) {
					errorCode = err.error;
				}
				if (err.message != null) {
					message = err.message;
				}
			}
		} catch (Exception ignored) {
			// 非 JSON 响应体，保留默认 message。
		}
		return new ApiException(code, errorCode, message);
	}
}
