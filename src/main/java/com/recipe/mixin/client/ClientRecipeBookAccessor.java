package com.recipe.mixin.client;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 暴露客户端配方书内部的 known 映射，用于在合并本地配方前做去重判断。
 */
@Mixin(ClientRecipeBook.class)
public interface ClientRecipeBookAccessor {
	@Accessor("known")
	Map<RecipeDisplayId, RecipeDisplayEntry> getrecipes$getKnown();
}
