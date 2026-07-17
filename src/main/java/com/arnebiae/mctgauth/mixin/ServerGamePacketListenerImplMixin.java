package com.arnebiae.mctgauth.mixin;

import com.arnebiae.mctgauth.McTgAuthMod;
import com.arnebiae.mctgauth.auth.AuthManager;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.UUID;

/**
 * 冻结玩家的封包拦截，运行在 netty 线程。
 *
 * 采用“默认拒绝”白名单：在协议分发的唯一入口 {@code shouldHandleMessage} 处，
 * 冻结时丢弃一切不在白名单内的封包（vanilla 对 {@code shouldHandleMessage} 返回 false 的
 * 封包会静默丢弃，无断连、无报错）。这一次性堵死所有会改动世界/背包/玩家状态的封包
 * （创造槽注入、playerCommand、clientCommand、配方书、容器/物品族、告示牌等），
 * 并防止未来新增封包留下缺口——黑名单天然漏项，白名单不会。
 *
 * 白名单内仍需精细过滤的封包由下方逻辑继续处理：
 *  - 移动：放行纯转头，取消越位并请求拉回（handleMovePlayer 注入）；
 *  - 命令：仅放行 /account（handleChatCommand 注入）；
 *  - 自定义载荷：按通道 ID 白名单过滤，整类放行会让任意模组注册的
 *    C2S 接收器（可能改动物品、经济、传送等状态）绕过冻结。
 * 只读并发冻结集，任何游戏状态改动都通过 AuthManager 调度回主线程。
 */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	/**
	 * 冻结期间放行的封包白名单。仅含两类：
	 *  (1) 连接保活 / 流控 / 客户端设置等无副作用封包；
	 *  (2) 需要下游精细过滤的移动与命令/聊天封包。
	 * 其余一律丢弃。用 isInstance 判定以覆盖 ServerboundMovePlayerPacket 的
	 * Pos/Rot/PosRot/StatusOnly 子类。高频封包置于前部以便短路。
	 */
	private static final Class<?>[] MCTGAUTH$FROZEN_WHITELIST = {
		// —— 高频，前置短路 ——
		ServerboundMovePlayerPacket.class,           // 环顾四周 + 越位拉回（下游 handleMovePlayer 过滤）
		ServerboundKeepAlivePacket.class,            // 连接保活
		ServerboundAcceptTeleportationPacket.class,  // 位置重同步的传送确认，必须放行
		// —— 命令 / 聊天（下游 filterCommand 仅放行 /account；聊天交 Fabric 事件拦截并提示）——
		ServerboundChatCommandPacket.class,
		ServerboundChatCommandSignedPacket.class,
		ServerboundChatPacket.class,
		ServerboundChatAckPacket.class,              // 聊天计数确认，签名命令依赖
		ServerboundChatSessionUpdatePacket.class,    // 注册签名公钥，签名命令依赖
		// —— 连接 / 流控 / 设置，无副作用 ——
		ServerboundPongPacket.class,
		ServerboundClientInformationPacket.class,    // 客户端语言/视距等设置
		ServerboundResourcePackPacket.class,         // 资源包状态，避免要求资源包的服务器误踢
		ServerboundConfigurationAcknowledgedPacket.class, // 切回配置阶段确认
		ServerboundClientTickEndPacket.class,        // 客户端 tick 边界标记
		ServerboundPlayerLoadedPacket.class,         // 客户端世界加载完成信号
		ServerboundChunkBatchReceivedPacket.class,   // 区块批次流控，缺则区块停止下发
	};

	/**
	 * 冻结期间放行的自定义载荷通道白名单。自定义载荷不能整类放行：
	 * 任意模组注册的 C2S 接收器都可能带游戏副作用。仅放行两类无副作用通道：
	 *  - minecraft:brand — 客户端 brand 上报；
	 *  - minecraft:register / minecraft:unregister — Fabric API 通道通告，
	 *    只更新服务端记录的客户端可用通道，丢弃会破坏解冻后的模组通信。
	 */
	private static final Set<Identifier> MCTGAUTH$ALLOWED_PAYLOAD_IDS = Set.of(
		BrandPayload.TYPE.id(),
		Identifier.withDefaultNamespace("register"),
		Identifier.withDefaultNamespace("unregister"));

	/**
	 * 协议分发入口：冻结时对非白名单封包返回 false，令 vanilla 静默丢弃。
	 * 白名单封包放行，交由 vanilla 及下游 handle* 注入继续处理。
	 */
	@Inject(method = "shouldHandleMessage", at = @At("HEAD"), cancellable = true)
	private void mctgauth$gateFrozen(Packet<?> packet, CallbackInfoReturnable<Boolean> cir) {
		if (!isFrozen()) {
			return;
		}
		// 自定义载荷按通道 ID 过滤，不进入整类白名单。
		if (packet instanceof ServerboundCustomPayloadPacket custom) {
			if (!MCTGAUTH$ALLOWED_PAYLOAD_IDS.contains(custom.payload().type().id())) {
				cir.setReturnValue(false);
			}
			return;
		}
		for (Class<?> allowed : MCTGAUTH$FROZEN_WHITELIST) {
			if (allowed.isInstance(packet)) {
				return; // 放行
			}
		}
		cir.setReturnValue(false); // 冻结期间丢弃所有非白名单封包
	}

	@Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
	private void mctgauth$onMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		if (!isFrozen()) {
			return;
		}
		// 纯转头包放行，允许玩家冻结期间环顾四周。
		if (!packet.hasPosition()) {
			return;
		}
		ci.cancel();
		// 被取消的移动包不会更新服务端位置，客户端会在本地自由走动，
		// 必须主动把客户端拉回。冻结期间服务端位置不变，netty 线程读取安全。
		// 仅在客户端坐标实际偏离时请求拉回，避免 teleport 确认包引发循环。
		double dx = packet.getX(player.getX()) - player.getX();
		double dy = packet.getY(player.getY()) - player.getY();
		double dz = packet.getZ(player.getZ()) - player.getZ();
		if (dx * dx + dy * dy + dz * dz > 1.0e-4) {
			AuthManager.requestResync(player.getUUID());
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
