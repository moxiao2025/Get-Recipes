package com.recipe.mixin;

import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 暴露 RecipeManager 中由 finalizeRecipeLoading 生成的全部展示信息
 * （ServerDisplayInfo 内包含已经分组、过滤特性开关的 RecipeDisplayEntry）。
 */
@Mixin(RecipeManager.class)
public interface RecipeManagerAccessor {
	@Accessor("allDisplays")
	List<RecipeManager.ServerDisplayInfo> getrecipes$getAllDisplays();
}
