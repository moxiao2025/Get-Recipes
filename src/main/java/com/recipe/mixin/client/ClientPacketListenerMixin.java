package com.recipe.mixin.client;

import com.recipe.GetRecipes;
import com.recipe.client.LocalRecipeUnlocker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在进入游戏（登录包处理完毕、玩家与客户端配方书已创建、注册表与特性开关就绪）时
 * 启动本地配方加载。
 *
 * 这里不使用 Fabric API 的 ClientPlayConnectionEvents，保证本 mod 在客户端
 * 没有安装 Fabric API 时依然能正常工作（服务端 Mixin 本来就不依赖 Fabric API，
 * 这也是之前单人可用、纯多人场景失效的原因）。
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void getrecipes$startLocalRecipeLoad(final ClientboundLoginPacket packet, final CallbackInfo ci) {
		LocalRecipeUnlocker.onJoin((ClientPacketListener) (Object) this);
	}

	/**
	 * 记录服务器下发的配方书包，便于区分"服务器未解锁"与"客户端未显示"。
	 * 仅打印条目数与是否 replace，不改变原版行为。
	 */
	@Inject(method = "handleRecipeBookAdd", at = @At("HEAD"))
	private void getrecipes$logServerRecipeBook(final ClientboundRecipeBookAddPacket packet, final CallbackInfo ci) {
		GetRecipes.LOGGER.info(
			"GetRecipes: server sent {} recipe book entr(ies) (replace={})",
			packet.entries().size(), packet.replace()
		);
	}
}
