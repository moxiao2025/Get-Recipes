package com.recipe.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在服务端向玩家发送初始配方书时，先把所有配方静默加入 "known" 集合。
 *
 * 这样做的原因：26.2 的配方书协议中，服务器只同步已解锁配方的展示数据，
 * 且服务端在处理 ServerboundPlaceRecipePacket（配方书点击填充合成格）时会
 * 校验 ServerRecipeBook#contains，纯客户端解锁会被拒绝。
 * 在服务端（单人的集成服务器或安装了本 mod 的专用服务器）解锁则可以：
 *   1. 客户端配方书收到全部配方展示（全部可见）；
 *   2. 左键点击配方时服务端校验通过，按原版逻辑填充合成格或显示幽灵配方。
 */
@Mixin(ServerRecipeBook.class)
public abstract class ServerRecipeBookMixin {

    @Shadow
    public abstract void add(ResourceKey<Recipe<?>> id);

    @Inject(method = "sendInitialRecipeBook", at = @At("HEAD"))
    private void getrecipes$unlockAllRecipes(ServerPlayer player, CallbackInfo ci) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        int added = 0;
        // 与原版 ServerRecipeBook#addRecipes 一致：跳过特殊配方（无法在配方书中放置）
        for (RecipeHolder<?> recipe : server.getRecipeManager().getRecipes()) {
            if (!recipe.value().isSpecial()) {
                this.add(recipe.id());
                added++;
            }
        }
        if (added > 0) {
            com.recipe.GetRecipes.LOGGER.debug("Unlocked {} recipes for {}", added, player.getGameProfile().name());
        }
    }
}
