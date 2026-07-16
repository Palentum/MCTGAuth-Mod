package com.arnebiae.mctgauth.http.dto;

import com.google.gson.annotations.SerializedName;

/** GET /api/v1/binding/{mc_uuid} 响应。 */
public class BindingResponse {
	public boolean bound;

	@SerializedName("tg_user_id")
	public Long tgUserId;

	@SerializedName("mc_name")
	public String mcName;
}
