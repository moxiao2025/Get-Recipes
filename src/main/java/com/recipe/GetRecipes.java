package com.recipe;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetRecipes implements ModInitializer, ClientModInitializer {
    public static final String MOD_ID = "getrecipes";
    public static final Logger LOGGER = LoggerFactory.getLogger("GetRecipes");

    @Override
    public void onInitialize() {
        // 服务端解锁：单人集成服务器 / 安装了本 mod 的专用服务器，由 ServerRecipeBookMixin 完成
        LOGGER.info("GetRecipes loaded: all recipes will be unlocked in the recipe book");
    }

    @Override
    public void onInitializeClient() {
        // 客户端解锁完全由 Mixin 驱动（ClientPacketListenerMixin / ClientRecipeBookMixin /
        // RecipeBookComponentMixin），不依赖 Fabric API，未安装本 mod 的服务器也能看到全部配方
        LOGGER.info("GetRecipes client loaded");
    }
}
