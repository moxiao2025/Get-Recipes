package net.minecraft.world.item.crafting;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.profiling.InactiveProfiler;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端本地配方加载辅助类。
 *
 * 与 {@link RecipeManager} 放在同一个包中，以便直接调用其 protected 的
 * apply（原版数据包加载的应用入口）。
 *
 * 不直接复用原版 SimpleJsonResourceReloadListener.scanDirectory 的原因：
 * 连接旧版本服务器（例如通过 ViaFabricPlus）时，服务器同步的注册表/标签可能
 * 缺少新版内容，导致部分本地配方在解析期失败，而原版扫描器会为每个失败文件
 * 打印一条 ERROR 造成日志刷屏。这里改为静默解析：失败的配方只计数跳过，
 * 它们在该服务器上本来就不可用。
 */
public final class GetRecipesLocalLoader {
	private GetRecipesLocalLoader() {
	}

	/**
	 * 扫描并解析资源包中的 data/&lt;namespace&gt;/recipe/*.json，生成 RecipeMap 后
	 * 应用到给定的 RecipeManager。
	 *
	 * @return 长度为 2 的数组：[0]=成功加载的配方数，[1]=解析失败被跳过的配方数
	 */
	public static int[] loadSilently(
		final RecipeManager recipeManager, final ResourceManager resources, final HolderLookup.Provider registries
	) {
		FileToIdConverter lister = FileToIdConverter.registry(Registries.RECIPE);
		DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		Map<Identifier, Recipe<?>> parsed = new HashMap<>();
		int failed = 0;

		for (Map.Entry<Identifier, Resource> entry : lister.listMatchingResources(resources).entrySet()) {
			Identifier file = entry.getKey();
			Identifier id = lister.fileToId(file);
			try (Reader reader = entry.getValue().openAsReader()) {
				DataResult<Recipe<?>> result = Recipe.CODEC.parse(ops, StrictJsonParser.parse(reader));
				if (result.result().isPresent()) {
					parsed.putIfAbsent(id, result.result().get());
				} else {
					failed++;
				}
			} catch (RuntimeException | java.io.IOException e) {
				failed++;
			}
		}

		List<RecipeHolder<?>> holders = new ArrayList<>(parsed.size());
		parsed.forEach((id, recipe) -> holders.add(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, id), recipe)));
		recipeManager.apply(RecipeMap.create(holders), resources, InactiveProfiler.INSTANCE);
		return new int[]{holders.size(), failed};
	}
}
