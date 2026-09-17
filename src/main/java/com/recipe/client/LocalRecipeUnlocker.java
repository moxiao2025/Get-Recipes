package com.recipe.client;

import com.recipe.GetRecipes;
import com.recipe.mixin.RecipeManagerAccessor;
import com.recipe.mixin.client.ClientRecipeBookAccessor;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.GetRecipesLocalLoader;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 纯客户端的全配方解锁逻辑（不依赖 Fabric API、不依赖服务器是否安装本 mod）。
 *
 * 26.2 的协议中，服务器不再向客户端同步完整配方，只通过
 * ClientboundRecipeBookAddPacket 下发"已解锁"配方的展示数据（RecipeDisplayEntry）；
 * 点击配方时还要由服务端校验 RecipeDisplayId 与解锁状态。因此在没有安装本 mod 的
 * 专用服务器上，客户端天然只能看到少量已解锁配方。
 *
 * 解决办法：进入服务器后，用连接同步到的注册表与特性开关，在客户端用本地资源包
 * （原版内置数据包 + 客户端 Mod 自带数据包）构建一个本地 {@link RecipeManager}，
 * 生成与服务端完全一致的 RecipeDisplayEntry，再合并进客户端配方书。
 *
 * 本地条目的 ID 统一加上 {@link #LOCAL_RECIPE_ID_BASE} 偏移：
 *   - 不与服务端自增的小索引冲突，服务端的 remove 包不会误删本地条目；
 *   - 点击时该 ID 发到原版服务端会被边界检查忽略（静默，无任何副作用），
 *     由 {@link com.recipe.mixin.client.RecipeBookComponentMixin} 在本地补幽灵配方提示。
 */
public final class LocalRecipeUnlocker {
	/** 本地配方展示 ID 的高位偏移，远大于任何真实服务端的 display 索引。 */
	public static final int LOCAL_RECIPE_ID_BASE = 0x40000000;

	private static volatile List<RecipeDisplayEntry> entries = List.of();

	private LocalRecipeUnlocker() {
	}

	/**
	 * 进入游戏时由 ClientPacketListenerMixin 调用。配方解析在 IO 线程进行
	 * （与原版资源重载一致），完成后回到客户端主线程刷新配方书。
	 */
	public static void onJoin(final ClientPacketListener handler) {
		// 立刻丢弃上一次连接的数据，避免重连完成前被误用
		entries = List.of();

		final Minecraft client = Minecraft.getInstance();

		// 单人世界由集成服务器上的 ServerRecipeBookMixin 处理，无需本地解析。
		if (client.getSingleplayerServer() != null) {
			return;
		}

		final HolderLookup.Provider registries = handler.registryAccess();
		final FeatureFlagSet features = handler.enabledFeatures();

		// 客户端当前的 ResourceManager 是 CLIENT_RESOURCES 视角（只能看到 /assets），
		// 配方 JSON 在 /data 下，必须复用同一批已打开的包按 SERVER_DATA 重新组装。
		// 注意：不能 close 这个新管理器，否则会关掉共享的底层资源包。
		// 客户端实现是 ReloadableResourceManager，其 listPacks() 会透传到底层的
		// MultiPackResourceManager，返回当前实际打开的全部资源包（去重）。
		ResourceManager activeManager = client.getResourceManager();
		List<PackResources> openPacks = activeManager.listPacks().toList();
		final MultiPackResourceManager dataResources = new MultiPackResourceManager(PackType.SERVER_DATA, openPacks);

		GetRecipes.LOGGER.info(
			"GetRecipes: joined a multiplayer world, loading local recipes from {} open pack(s)",
			openPacks.size()
		);

		Util.ioPool().execute(() -> {
			List<RecipeDisplayEntry> loaded = List.of();
			try {
				int jsonFiles = dataResources.listResources("recipe", location -> location.getPath().endsWith(".json")).size();
				GetRecipes.LOGGER.info("GetRecipes: found {} recipe JSON file(s) under the data section", jsonFiles);

				RecipeManager recipeManager = new RecipeManager(registries);
				int[] counts = GetRecipesLocalLoader.loadSilently(recipeManager, dataResources, registries);
				recipeManager.finalizeRecipeLoading(features);
				GetRecipes.LOGGER.info(
					"GetRecipes: parsed {} local recipe(s) ({} skipped: missing items/tags this server does not have)",
					counts[0], counts[1]
				);

				List<RecipeManager.ServerDisplayInfo> displays =
					((RecipeManagerAccessor) (Object) recipeManager).getrecipes$getAllDisplays();
				List<RecipeDisplayEntry> result = new ArrayList<>(displays.size());
				int index = 0;
				for (RecipeManager.ServerDisplayInfo info : displays) {
					RecipeDisplayEntry display = info.display();
					result.add(
						new RecipeDisplayEntry(
							new RecipeDisplayId(LOCAL_RECIPE_ID_BASE + index++),
							display.display(),
							display.group(),
							display.category(),
							display.craftingRequirements()
						)
					);
				}
				loaded = result;
				GetRecipes.LOGGER.info("GetRecipes: loaded {} recipe displays from local data packs", result.size());
			} catch (RuntimeException e) {
				GetRecipes.LOGGER.error("GetRecipes: failed to load local recipes for the client recipe book", e);
			}

			final List<RecipeDisplayEntry> finalLoaded = loaded;
			client.execute(() -> {
				entries = finalLoaded;
				if (!finalLoaded.isEmpty()) {
					refreshRecipeBook(client);
				}
			});
		});
	}

	/**
	 * 合并逻辑是否应当生效：本地配方已加载，且当前不是单人游戏
	 * （单人游戏的集成服务器已由 ServerRecipeBookMixin 解锁全部配方）。
	 */
	public static boolean isActive() {
		return !entries.isEmpty() && Minecraft.getInstance().getSingleplayerServer() == null;
	}

	/**
	 * 将本地配方合并进指定的客户端配方书，已存在（按内容判重）的条目跳过。
	 * 在客户端主线程随 ClientRecipeBook#rebuildCollections 一起调用。
	 */
	public static void mergeInto(final ClientRecipeBook book) {
		List<RecipeDisplayEntry> local = entries;
		if (local.isEmpty() || Minecraft.getInstance().getSingleplayerServer() != null) {
			return;
		}

		Map<RecipeDisplayId, RecipeDisplayEntry> known =
			((ClientRecipeBookAccessor) book).getrecipes$getKnown();
		Set<EntryKey> existing = new HashSet<>(known.size() * 2);
		known.values().forEach(entry -> existing.add(EntryKey.of(entry)));

		int added = 0;
		for (RecipeDisplayEntry entry : local) {
			// ID 冲突防御 + 按内容去重（服务器也装了本 mod 时，其下发的条目内容相同）
			if (!known.containsKey(entry.id()) && existing.add(EntryKey.of(entry))) {
				book.add(entry);
				added++;
			}
		}
		if (added > 0) {
			GetRecipes.LOGGER.info(
				"GetRecipes: merged {} local recipes into the client recipe book ({} entries already known from the server)",
				added, known.size() - added
			);
		}
	}

	/** 根据本地偏移 ID 取回条目，非本地 ID 返回 null。 */
	public static RecipeDisplayEntry getEntry(final RecipeDisplayId id) {
		int index = id.index() - LOCAL_RECIPE_ID_BASE;
		List<RecipeDisplayEntry> local = entries;
		return index >= 0 && index < local.size() ? local.get(index) : null;
	}

	private static void refreshRecipeBook(final Minecraft client) {
		if (client.player == null || client.getSingleplayerServer() != null) {
			return;
		}

		ClientRecipeBook book = client.player.getRecipeBook();
		mergeInto(book);
		book.rebuildCollections();

		ClientPacketListener connection = client.getConnection();
		if (connection != null && client.level != null) {
			connection.searchTrees().updateRecipes(book, client.level);
		}
		if (client.gui.screen() instanceof RecipeUpdateListener listener) {
			listener.recipesUpdated();
		}
	}

	/**
	 * 按内容标识一个展示条目（RecipeDisplay 的各实现均为 record，具备值相等语义）。
	 */
	private record EntryKey(
		RecipeDisplay display, RecipeBookCategory category, OptionalInt group,
		Optional<List<Ingredient>> craftingRequirements
	) {
		static EntryKey of(final RecipeDisplayEntry entry) {
			return new EntryKey(entry.display(), entry.category(), entry.group(), entry.craftingRequirements());
		}
	}
}
