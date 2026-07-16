package com.arnebiae.mctgauth;

import com.arnebiae.mctgauth.auth.AuthManager;
import com.arnebiae.mctgauth.auth.Messages;
import com.arnebiae.mctgauth.command.AccountCommand;
import com.arnebiae.mctgauth.config.ModConfig;
import com.arnebiae.mctgauth.event.FreezeEvents;
import com.arnebiae.mctgauth.http.BotApiClient;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McTgAuthMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "mctgauth";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// 供 netty 线程上的 mixin 访问的单例引用。
	private static volatile AuthManager authManager;

	@Override
	public void onInitializeServer() {
		ModConfig config = ModConfig.load();
		Messages messages = new Messages(config);
		BotApiClient api = new BotApiClient(config);
		AuthManager auth = new AuthManager(config, api, messages);
		authManager = auth;

		// 生命周期：捕获 / 释放服务器引用，停止时关闭 HTTP 客户端。
		ServerLifecycleEvents.SERVER_STARTING.register(auth::setServer);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> api.shutdown());

		// 注册命令。
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				AccountCommand.register(dispatcher, auth));

		// 注册冻结相关事件。
		FreezeEvents.register(auth);

		LOGGER.info("MC Telegram Auth 已初始化，Bot 服务地址：{}", config.apiBaseUrl);
	}

	/** 供 mixin 使用；服务器完成初始化后非空。 */
	public static AuthManager getAuthManager() {
		return authManager;
	}
}
