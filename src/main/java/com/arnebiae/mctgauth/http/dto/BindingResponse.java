package com.arnebiae.mctgauth.http.dto;

import com.google.gson.annotations.SerializedName;

/** GET /api/v1/binding/{mc_uuid} 响应。 */
public class BindingResponse {
	/** 装箱类型以便区分「字段缺失」与 false；BotApiClient 校验后对调用方保证非 null。 */
	public Boolean bound;

	@SerializedName("tg_user_id")
	public Long tgUserId;

	@SerializedName("mc_name")
	public String mcName;
}
