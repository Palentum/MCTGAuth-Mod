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
		if (placeholders.length < 2 || text.indexOf('%') < 0) {
			return text;
		}
		// 单遍扫描：逐个匹配模板中的 %key%，命中即写入替换值。
		// 替换值一经写入便不再被后续占位符二次扫描，从根上消除
		// “某占位符值恰好含 %其他键% 时被连带替换”的顺序相关污染，
		// 使本地渲染与外部（Bot）token 字符集彻底解耦。
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		int n = text.length();
		while (i < n) {
			int start = text.indexOf('%', i);
			if (start < 0) {
				out.append(text, i, n);
				break;
			}
			int end = text.indexOf('%', start + 1);
			if (end < 0) {
				out.append(text, i, n);
				break;
			}
			String value = lookup(text, start + 1, end, placeholders);
			if (value != null) {
				out.append(text, i, start).append(value);
				i = end + 1;
			} else {
				// 非占位符：保留起始 % 原样，从其后继续（支持相邻 %a%%b%、孤立 %）。
				out.append(text, i, start + 1);
				i = start + 1;
			}
		}
		return out.toString();
	}

	/** 在 placeholders 成对键中查找 text[keyStart, keyEnd) 对应的替换值，缺失返回 null。 */
	private static String lookup(String text, int keyStart, int keyEnd, String[] placeholders) {
		int len = keyEnd - keyStart;
		for (int i = 0; i + 1 < placeholders.length; i += 2) {
			String name = placeholders[i];
			if (name.length() == len && text.regionMatches(keyStart, name, 0, len)) {
				return placeholders[i + 1];
			}
		}
		return null;
	}

	/** 取文案并转为 Component。 */
	public MutableComponent get(String key, String... placeholders) {
		return Component.literal(raw(key, placeholders));
	}
}
