package com.arnebiae.mctgauth.mixin;

import com.arnebiae.mctgauth.McTgAuthMod;
import com.arnebiae.mctgauth.auth.AuthManager;

import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 在 netty 线程上拦截冻结玩家的移动 / 载具 / 动作 / 背包 / 命令包（黑名单机制）。
 * 只读并发冻结集，任何游戏状态改动都通过 AuthManager 调度回主线程。
 */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
	private void mctgauth$onMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		Vec3 freezePos = AuthManager.getFreezePosition(player.getUUID());
		if (freezePos == null) {
			return; // 未冻结
		}
		// 纯转头包放行，允许玩家冻结期间环顾四周。
		if (!packet.hasPosition()) {
			return;
		}
		ci.cancel();
		// 被取消的移动包不会更新服务端位置，客户端会在本地自由走动，
		// 必须主动把客户端拉回。偏移比较基准用冻结点的并发镜像，而非
		// ServerPlayer 实时坐标——后者归主线程所有，外力（水流、活塞、
		// 击退）随时改动，netty 线程读取存在数据竞争。仅在客户端坐标
		// 实际偏离冻结点时请求拉回：teleport 确认包的位置恰为冻结点，
		// 偏移为零不置位，避免拉回循环。
		double dx = packet.getX(freezePos.x) - freezePos.x;
		double dy = packet.getY(freezePos.y) - freezePos.y;
		double dz = packet.getZ(freezePos.z) - freezePos.z;
		if (dx * dx + dy * dy + dz * dz > 1.0e-4) {
			AuthManager.requestResync(player.getUUID());
		}
	}

	@Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
	private void mctgauth$onMoveVehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
		if (isFrozen()) {
			ci.cancel();
		}
	}

	@Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
	private void mctgauth$onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
		if (isFrozen()) {
			ci.cancel();
		}
	}

	@Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
	private void mctgauth$onContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
		if (isFrozen()) {
			ci.cancel();
		}
	}

	@Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
	private void mctgauth$onChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
		filterCommand(packet.command(), ci);
	}

	@Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
	private void mctgauth$onSignedChatCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
		filterCommand(packet.command(), ci);
	}

	/** 冻结时仅放行 account 命令，其余取消并给出节流提示。 */
	private void filterCommand(String command, CallbackInfo ci) {
		if (!isFrozen()) {
			return;
		}
		if (isAccountCommand(command)) {
			return;
		}
		ci.cancel();
		AuthManager auth = McTgAuthMod.getAuthManager();
		if (auth != null) {
			auth.sendThrottledFrozenHint(player.getUUID());
		}
	}

	private static boolean isAccountCommand(String command) {
		// 命令字符串不含前导斜杠。
		return command != null && (command.equals("account") || command.startsWith("account "));
	}

	private boolean isFrozen() {
		UUID uuid = player.getUUID();
		return AuthManager.isFrozen(uuid);
	}
}
