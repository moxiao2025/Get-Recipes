package com.recipe.mixin.client;

import com.recipe.client.LocalRecipeUnlocker;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在未安装本 mod 的服务器上，点击本地配方时服务端会忽略占位请求（静默），
 * 因此在原版发包流程之后，于客户端本地直接展示幽灵配方（材料摆放提示），
 * 行为与服务端在材料不足时回传 ClientboundPlaceGhostRecipePacket 一致。
 * 服务器已安装本 mod 时，所有条目都由服务端下发（低位 ID），不会触发这里。
 */
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {

	@Inject(method = "tryPlaceRecipe", at = @At("RETURN"))
	private void getrecipes$showGhostRecipeForLocalEntry(
		final RecipeCollection collection, final RecipeDisplayId recipe, final boolean useMaxItems,
		final CallbackInfoReturnable<Boolean> cir
	) {
		if (cir.getReturnValueZ() && LocalRecipeUnlocker.isActive()) {
			RecipeDisplayEntry entry = LocalRecipeUnlocker.getEntry(recipe);
			if (entry != null) {
				((RecipeBookComponent) (Object) this).fillGhostRecipe(entry.display());
			}
		}
	}
}
