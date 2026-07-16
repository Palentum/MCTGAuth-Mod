package com.arnebiae.mctgauth.auth;

import com.arnebiae.mctgauth.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** 从配置解析玩家可见文案，支持 %key% 占位符替换。 */
public class Messages {
	private final ModConfig config;

	public Messages(ModConfig config) {
		this.config = config;
	}

	/** 取原始字符串（已做占位符替换）；键缺失时返回键名本身以便排查。 */
	public String raw(String key, String... placeholders) {
		String text = config.messages.get(key);
		if (text == null) {
			text = key;
		}
		// placeholders 形如 "token", "<值>", "bot", "<值>" 成对出现。
		for (int i = 0; i + 1 < placeholders.length; i += 2) {
			text = text.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
		}
		return text;
	}

	/** 取文案并转为 Component。 */
	public MutableComponent get(String key, String... placeholders) {
		return Component.literal(raw(key, placeholders));
	}
}
