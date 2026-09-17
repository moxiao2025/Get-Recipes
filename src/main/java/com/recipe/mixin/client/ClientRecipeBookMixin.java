package com.recipe.mixin.client;

import com.recipe.client.LocalRecipeUnlocker;
import net.minecraft.client.ClientRecipeBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每次客户端配方书重建集合（服务器初始下发、增删配方、打开界面等）之前，
 * 把本地加载的全部配方合并进去，保证未安装本 mod 的服务器上配方书也是全的。
 *
 * 合并逻辑内部会：
 *   1. 单人游戏直接跳过（服务端 Mixin 已经解锁，避免重复显示）；
 *   2. 按配方内容去重，避免与已安装本 mod 的服务器下发的配方重复；
 *   3. 本地配方使用高位 ID，不会被服务器的 remove 包误删。
 */
@Mixin(ClientRecipeBook.class)
public abstract class ClientRecipeBookMixin {

	@Inject(method = "rebuildCollections", at = @At("HEAD"))
	private void getrecipes$mergeLocalRecipes(final CallbackInfo ci) {
		LocalRecipeUnlocker.mergeInto((ClientRecipeBook) (Object) this);
	}
}
