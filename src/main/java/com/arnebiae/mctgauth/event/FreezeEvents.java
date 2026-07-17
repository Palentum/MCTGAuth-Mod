package com.arnebiae.mctgauth.event;

import com.arnebiae.mctgauth.auth.AuthManager;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 通过 Fabric API 事件拦截冻结玩家的破坏 / 攻击 / 使用 / 聊天等行为。
 * 移动 / 载具 / 背包 / 命令包在 mixin 中拦截。
 */
public final class FreezeEvents {
	private FreezeEvents() {
	}

	public static void register(AuthManager auth) {
		// 加入 / 离线。
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				auth.onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				auth.onDisconnect(handler.getPlayer()));

		// 重生：死亡玩家点重生回到世界后，未认证时以新位置重新冻结（避免卡死在死亡界面）。
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
				auth.onRespawn(newPlayer));

		// 每 tick 驱动。
		ServerTickEvents.END_SERVER_TICK.register(auth::onEndServerTick);

		// 破坏方块：冻结时取消。
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				!isFrozen(player));

		// 攻击 / 使用：冻结时返回 FAIL。
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
				isFrozen(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
				isFrozen(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
				isFrozen(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
				isFrozen(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
				isFrozen(player) ? InteractionResult.FAIL : InteractionResult.PASS);

		// 聊天：冻结时拦截并给出节流提示。
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			UUID uuid = sender.getUUID();
			if (AuthManager.isFrozen(uuid)) {
				auth.sendThrottledFrozenHint(uuid);
				return false;
			}
			return true;
		});
	}

	private static boolean isFrozen(Player player) {
		return AuthManager.isFrozen(player.getUUID());
	}
}
