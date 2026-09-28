package net.minecraft.world.item.crafting;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.world.flag.FeatureFlagSet;

import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 客户端本地配方加载辅助类。
 *
 * 与 {@link RecipeManager} 放在同一个包中，以便访问其 private 的
 * unpackRecipeInfo（配方书展示条目的生成入口）。
 *
 * 跨版本说明（26.1–26.3 单 jar）：
 * <ul>
 *   <li>26.3 起 RecipeManager 不再有 apply(RecipeMap, ...)，recipes 字段改为 final
 *       且仅能由构造器填充；但 unpackRecipeInfo(Iterable, FeatureFlagSet) 私有静态
 *       方法在 26.1–26.3 中签名一致，且不依赖实例状态，因此直接对解析出的
 *       RecipeHolder 列表调用它即可拿到与原版 finalizeRecipeLoading 相同的展示条目，
 *       无需构造 RecipeManager，也无需 RecipeMap；</li>
 *   <li>Recipe.CODEC 在 26.1/26.2 产出 Recipe，26.3 起产出 Holder&lt;Recipe&gt;，
 *       泛型在运行期擦除，按实际值类型解包即可双版本兼容。</li>
 * </ul>
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

	/** RecipeManager#unpackRecipeInfo(Iterable, FeatureFlagSet) 的反射缓存。 */
	private static volatile Method unpackRecipeInfo;

	/** 加载结果：found=扫描到的 JSON 文件数，loaded=成功解析数，failed=跳过数。 */
	public record LoadResult(int found, int loaded, int failed, List<RecipeManager.ServerDisplayInfo> displays) {
	}

	/**
	 * 扫描并解析资源包中的 data/&lt;namespace&gt;/recipe/*.json，
	 * 生成配方书展示条目（已按特性开关过滤）。
	 */
	public static LoadResult loadSilently(
		final ResourceManager resources, final HolderLookup.Provider registries, final FeatureFlagSet features
	) {
		FileToIdConverter lister = FileToIdConverter.registry(Registries.RECIPE);
		DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		List<RecipeHolder<?>> holders = new ArrayList<>();
		int failed = 0;

		Map<Identifier, Resource> files = lister.listMatchingResources(resources);
		for (Map.Entry<Identifier, Resource> entry : files.entrySet()) {
			Identifier id = lister.fileToId(entry.getKey());
			try (Reader reader = entry.getValue().openAsReader()) {
				DataResult<?> result = Recipe.CODEC.parse(ops, StrictJsonParser.parse(reader));
				Recipe<?> recipe = unwrapRecipe(result.result().orElse(null));
				if (recipe != null) {
					holders.add(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, id), recipe));
				} else {
					failed++;
				}
			} catch (RuntimeException | java.io.IOException e) {
				failed++;
			}
		}

		return new LoadResult(files.size(), holders.size(), failed, unpackDisplays(holders, features));
	}

	/** 兼容 26.1/26.2（Recipe）与 26.3+（Holder&lt;Recipe&gt;）的编解码产出。 */
	private static Recipe<?> unwrapRecipe(final Object parsed) {
		if (parsed instanceof Recipe<?> recipe) {
			return recipe;
		}
		if (parsed instanceof Holder<?> holder && holder.value() instanceof Recipe<?> recipe) {
			return recipe;
		}
		return null;
	}

	/**
	 * 反射调用 RecipeManager#unpackRecipeInfo(Iterable, FeatureFlagSet)，
	 * 得到已分组、已过滤特性开关的 ServerDisplayInfo 列表。
	 */
	private static List<RecipeManager.ServerDisplayInfo> unpackDisplays(
		final List<RecipeHolder<?>> holders, final FeatureFlagSet features
	) {
		try {
			Method method = unpackRecipeInfo;
			if (method == null) {
				method = RecipeManager.class.getDeclaredMethod("unpackRecipeInfo", Iterable.class, FeatureFlagSet.class);
				if (!Modifier.isStatic(method.getModifiers())) {
					throw new NoSuchMethodException("unpackRecipeInfo is not static");
				}
				method.setAccessible(true);
				unpackRecipeInfo = method;
			}
			@SuppressWarnings("unchecked")
			List<RecipeManager.ServerDisplayInfo> displays =
				(List<RecipeManager.ServerDisplayInfo>) method.invoke(null, holders, features);
			return displays;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("GetRecipes: RecipeManager.unpackRecipeInfo unavailable", e);
		}
	}
}
