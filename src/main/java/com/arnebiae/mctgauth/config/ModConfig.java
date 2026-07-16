package com.arnebiae.mctgauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.arnebiae.mctgauth.McTgAuthMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模组配置，持久化到 config/mctgauth.json。
 * 首次运行写入完整默认值；所有玩家可见文案在 messages 中，均可配置。
 */
public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Bot 服务地址。 */
	public String apiBaseUrl = "http://127.0.0.1:8632";
	/** 与 Bot 服务约定的 Bearer 令牌。 */
	public String apiToken = "change-me";
	/** 未认证玩家的踢出超时（秒）。 */
	public int kickTimeoutSeconds = 120;
	/** 同 IP 免登录会话时长（分钟），0 表示禁用。 */
	public int ipSessionMinutes = 30;
	/** 轮询登录请求状态的间隔（tick）。 */
	public int pollIntervalTicks = 40;
	/** 全部玩家可见文案，中文，含 § 颜色码。 */
	public Map<String, String> messages = defaultMessages();

	private static Map<String, String> defaultMessages() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("needRegister", "§e[认证] §f你尚未绑定 Telegram 账号，请输入 §a/account register §f获取绑定链接。");
		m.put("needLogin", "§e[认证] §f请输入 §a/account login §f，然后在 Telegram 中点击“批准”完成登录。");
		m.put("registerToken", "§e[认证] §f请在 Telegram 中打开机器人 §b@%bot% §f并发送 §a/start %token%§f，或点击下方链接：");
		m.put("loginSent", "§e[认证] §f登录请求已发送，请到 Telegram 中点击“批准”。");
		m.put("loginApproved", "§a[认证] §f登录已批准，欢迎回来！");
		m.put("loginDenied", "§c[认证] §f登录请求已被拒绝。如需重试请再次输入 §a/account login§f。");
		m.put("loginExpired", "§c[认证] §f登录请求已过期，请重新输入 §a/account login§f。");
		m.put("loginCancelled", "§c[认证] §f登录请求已取消，请重新输入 §a/account login§f。");
		m.put("frozenHint", "§e[认证] §f你尚未完成登录，暂时无法进行该操作。请先完成 Telegram 认证。");
		m.put("kickTimeout", "§c认证超时：你未能在规定时间内完成 Telegram 登录，请重新进入服务器。");
		m.put("serviceUnavailable", "§c[认证] §f认证服务暂时不可用，正在重试，请稍候……");
		m.put("alreadyBound", "§e[认证] §f你已绑定 Telegram 账号，请输入 §a/account login §f进行登录。");
		m.put("notBound", "§c[认证] §f你尚未绑定 Telegram 账号，请先输入 §a/account register§f。");
		m.put("sessionRestored", "§a[认证] §f检测到近期同一 IP 的有效会话，已为你自动登录，欢迎回来！");
		m.put("alreadyPending", "§e[认证] §f你已有一个待处理的登录请求，请到 Telegram 中处理。");
		m.put("rateLimited", "§c[认证] §f操作过于频繁，请稍后再试。");
		m.put("alreadyLoggedIn", "§a[认证] §f你已经完成登录，无需重复操作。");
		m.put("playersOnly", "§c[认证] §f该命令只能由玩家执行。");
		m.put("registerLinkText", "§b§n点此打开 Telegram 完成绑定");
		return m;
	}

	/** 读取配置；文件不存在时写入默认值。 */
	public static ModConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("mctgauth.json");
		ModConfig config;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				config = GSON.fromJson(reader, ModConfig.class);
				if (config == null) {
					config = new ModConfig();
				}
			} catch (IOException e) {
				McTgAuthMod.LOGGER.error("读取配置文件失败，使用默认配置：{}", path, e);
				config = new ModConfig();
			}
			// 补齐可能缺失的字段（旧版本配置升级）。
			config.fillDefaults();
		} else {
			config = new ModConfig();
			config.save(path);
		}
		config.warnInsecureToken();
		return config;
	}

	/** 缺失的 messages 键用默认值补齐，避免读取到 null。 */
	private void fillDefaults() {
		if (messages == null) {
			messages = defaultMessages();
			return;
		}
		Map<String, String> defaults = defaultMessages();
		for (Map.Entry<String, String> e : defaults.entrySet()) {
			messages.putIfAbsent(e.getKey(), e.getValue());
		}
	}

	private void warnInsecureToken() {
		if (apiToken == null || apiToken.isEmpty() || "change-me".equals(apiToken)) {
			McTgAuthMod.LOGGER.warn("apiToken 尚未设置或仍为默认值 \"change-me\"，请在 config/mctgauth.json 中改为与 Bot 服务一致的强令牌。");
		}
	}

	private void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
			McTgAuthMod.LOGGER.info("已写入默认配置：{}", path);
		} catch (IOException e) {
			McTgAuthMod.LOGGER.error("写入默认配置失败：{}", path, e);
		}
	}
}
