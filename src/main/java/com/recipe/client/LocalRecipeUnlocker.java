package com.recipe.client;

import com.recipe.GetRecipes;
import com.recipe.mixin.RecipeManagerAccessor;
import com.recipe.mixin.client.ClientRecipeBookAccessor;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.GetRecipesLocalLoader;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

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
	/** 与 {@link #entries} 一一对应的预计算语义签名（在 IO 线程生成）。 */
	private static volatile List<String> entrySignatures = List.of();

	private LocalRecipeUnlocker() {
	}

	/**
	 * 进入游戏时由 ClientPacketListenerMixin 调用。配方解析在 IO 线程进行
	 * （与原版资源重载一致），完成后回到客户端主线程刷新配方书。
	 */
	public static void onJoin(final ClientPacketListener handler) {
		// 立刻丢弃上一次连接的数据，避免重连完成前被误用
		entries = List.of();
		entrySignatures = List.of();

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
			List<String> loadedSignatures = List.of();
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
				// 签名涉及反射，放在 IO 线程预计算，主线程合并时直接使用
				loadedSignatures = result.stream().map(LocalRecipeUnlocker::semanticSignature).toList();
				GetRecipes.LOGGER.info("GetRecipes: loaded {} recipe displays from local data packs", result.size());
			} catch (RuntimeException e) {
				GetRecipes.LOGGER.error("GetRecipes: failed to load local recipes for the client recipe book", e);
			}

			final List<RecipeDisplayEntry> finalLoaded = loaded;
			final List<String> finalSignatures = loadedSignatures;
			client.execute(() -> {
				entries = finalLoaded;
				entrySignatures = finalSignatures;
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
	 * 将本地配方合并进指定的客户端配方书，已存在（按语义内容判重）的条目跳过。
	 * 在客户端主线程随 ClientRecipeBook#rebuildCollections 一起调用。
	 */
	public static void mergeInto(final ClientRecipeBook book) {
		List<RecipeDisplayEntry> local = entries;
		List<String> localSigs = entrySignatures;
		if (local.isEmpty() || localSigs.isEmpty() || Minecraft.getInstance().getSingleplayerServer() != null) {
			return;
		}

		Map<RecipeDisplayId, RecipeDisplayEntry> known =
			((ClientRecipeBookAccessor) book).getrecipes$getKnown();
		int serverEntriesBefore = known.size();

		Set<String> existing = new HashSet<>(known.size() * 2);
		known.values().forEach(entry -> existing.add(semanticSignature(entry)));

		int added = 0;
		for (int i = 0; i < local.size(); i++) {
			RecipeDisplayEntry entry = local.get(i);
			// ID 冲突防御 + 按语义内容去重（服务器也装了本 mod 时，其下发的条目语义相同）
			if (known.containsKey(entry.id())) {
				continue;
			}
			if (!existing.add(localSigs.get(i))) {
				continue;
			}
			book.add(entry);
			added++;
		}
		if (added > 0) {
			GetRecipes.LOGGER.info(
				"GetRecipes: merged {} local recipes into the client recipe book ({} entries already known from the server)",
				added, serverEntriesBefore
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
		Screen current = getCurrentScreen(client);
		if (current instanceof RecipeUpdateListener listener) {
			listener.recipesUpdated();
		}
	}

	/**
	 * 跨版本取得当前打开的界面。
	 * 26.1：Minecraft.screen 字段；26.2+：Gui.screen() 方法，Minecraft.screen 已移除。
	 */
	private static Screen getCurrentScreen(final Minecraft client) {
		try {
			return (Screen) client.gui.getClass().getMethod("screen").invoke(client.gui);
		} catch (NoSuchMethodException ignored) {
			// 26.1 路径
		} catch (ReflectiveOperationException e) {
			return null;
		}
		try {
			return (Screen) Minecraft.class.getField("screen").get(client);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/**
	 * 一个展示条目的语义签名：
	 * 仅依赖"配方是什么"，不依赖本地/服务器各自分配的对象实例与临时索引。
	 * 因此不包含 RecipeDisplayEntry.id（本地伪造 ID，必然不同）和 group
	 * （group 是 RecipeManager 按配方遍历顺序临时内联的整数，
	 * 服务器与本地的配方集合/顺序不同，同一配方的 group 索引并不一致）。
	 */
	private static String semanticSignature(final RecipeDisplayEntry entry) {
		StringBuilder sb = new StringBuilder(256);
		sigRecipeDisplay(entry.display(), sb);
		sb.append("|cat=").append(BuiltInRegistries.RECIPE_BOOK_CATEGORY.getKey(entry.category()));
		sb.append("|req=");
		entry.craftingRequirements().ifPresentOrElse(
			list -> {
				sb.append('[');
				for (int i = 0; i < list.size(); i++) {
					if (i > 0) {
						sb.append(';');
					}
					// 材料判定只关心"接受哪些物品"，用注册名集合归一化，
					// 消除 标签/直连、本地/网络 HolderSet 实现差异。
					list.get(i).items()
						.map(LocalRecipeUnlocker::holderKey)
						.sorted()
						.forEach(sb::append);
				}
				sb.append(']');
			},
			() -> sb.append("none")
		);
		return sb.toString();
	}

	private static StringBuilder sigRecipeDisplay(final RecipeDisplay display, final StringBuilder sb) {
		sb.append("type=").append(BuiltInRegistries.RECIPE_DISPLAY.getKey(display.type())).append('{');
		// RecipeDisplay 的各实现均为 record，按组件逐个归一化即可覆盖所有合成/烧炼/切石/锻造显示。
		for (RecordComponent component : display.getClass().getRecordComponents()) {
			sb.append(component.getName()).append('=');
			try {
				sigValue(component.getAccessor().invoke(display), sb);
			} catch (ReflectiveOperationException e) {
				sb.append('?');
			}
			sb.append(',');
		}
		return sb.append('}');
	}

	private static StringBuilder sigValue(final Object value, final StringBuilder sb) {
		if (value == null) {
			return sb.append("null");
		}
		if (value instanceof SlotDisplay slotDisplay) {
			return sigSlotDisplay(slotDisplay, sb);
		}
		if (value instanceof Holder<?> holder) {
			return sb.append(holderKey(holder));
		}
		if (value instanceof DataComponentType<?> type) {
			return sb.append(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));
		}
		if (value instanceof Optional<?> optional) {
			sb.append("opt(");
			optional.ifPresent(v -> sigValue(v, sb));
			return sb.append(')');
		}
		if (value instanceof List<?> list) {
			sb.append('[');
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sigValue(list.get(i), sb);
			}
			return sb.append(']');
		}
		if (value instanceof Map<?, ?> map) {
			// Map 顺序不保证，按键排序保证签名稳定
			TreeMap<String, String> sorted = new TreeMap<>();
			map.forEach((k, v) -> sorted.put(sigValue(k, new StringBuilder()).toString(), sigValue(v, new StringBuilder()).toString()));
			return sb.append(sorted);
		}
		if (value.getClass().isRecord()) {
			sb.append(value.getClass().getSimpleName()).append('{');
			boolean first = true;
			for (RecordComponent component : value.getClass().getRecordComponents()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append(component.getName()).append('=');
				try {
					sigValue(component.getAccessor().invoke(value), sb);
				} catch (ReflectiveOperationException e) {
					sb.append('?');
				}
			}
			return sb.append('}');
		}
		if (value instanceof Enum<?> enumeration) {
			return sb.append(enumeration.name());
		}
		return sb.append(value);
	}

	private static StringBuilder sigSlotDisplay(final SlotDisplay display, final StringBuilder sb) {
		if (display instanceof SlotDisplay.Empty) {
			return sb.append("empty");
		}
		if (display instanceof SlotDisplay.AnyFuel) {
			return sb.append("any_fuel");
		}
		if (display instanceof SlotDisplay.ItemSlotDisplay d) {
			return sb.append("item{").append(holderKey(d.item())).append('}');
		}
		if (display instanceof SlotDisplay.TagSlotDisplay d) {
			return sb.append("tag{").append(d.tag().location()).append('}');
		}
		if (display instanceof SlotDisplay.Composite d) {
			sb.append("composite[");
			appendSlotList(d.contents(), sb);
			return sb.append(']');
		}
		if (display instanceof SlotDisplay.WithRemainder d) {
			sb.append("remainder(in=");
			sigSlotDisplay(d.input(), sb);
			sb.append(",rem=");
			sigSlotDisplay(d.remainder(), sb);
			return sb.append(')');
		}
		if (display instanceof SlotDisplay.ItemStackSlotDisplay d) {
			return sigValue(d.stack(), sb.append("stack{"));
		}
		if (display instanceof SlotDisplay.OnlyWithComponent d) {
			sb.append("with_component{");
			sigSlotDisplay(d.source(), sb);
			return sb.append('}');
		}
		if (display instanceof SlotDisplay.WithAnyPotion d) {
			sb.append("with_any_potion{");
			sigSlotDisplay(d.display(), sb);
			return sb.append('}');
		}
		if (display instanceof SlotDisplay.DyedSlotDemo d) {
			sb.append("dyed{target=");
			sigSlotDisplay(d.target(), sb);
			sb.append(",dye=");
			sigSlotDisplay(d.dye(), sb);
			return sb.append('}');
		}
		if (display instanceof SlotDisplay.SmithingTrimDemoSlotDisplay d) {
			sb.append("trim{base=");
			sigSlotDisplay(d.base(), sb);
			sb.append(",mat=");
			sigSlotDisplay(d.material(), sb);
			return sb.append(",pattern=").append(holderKey(d.pattern())).append('}');
		}
		// 未知的 SlotDisplay 类型：退回结构反射，保证前向版本兼容
		return sigValueRecordFallback(display, sb);
	}

	private static void appendSlotList(final List<SlotDisplay> displays, final StringBuilder sb) {
		for (int i = 0; i < displays.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sigSlotDisplay(displays.get(i), sb);
		}
	}

	private static StringBuilder sigValueRecordFallback(final SlotDisplay display, final StringBuilder sb) {
		sb.append(display.getClass().getSimpleName()).append('{');
		if (display.getClass().isRecord()) {
			boolean first = true;
			for (RecordComponent component : display.getClass().getRecordComponents()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append(component.getName()).append('=');
				try {
					sigValue(component.getAccessor().invoke(display), sb);
				} catch (ReflectiveOperationException e) {
					sb.append('?');
				}
			}
		} else {
			sb.append(display);
		}
		return sb.append('}');
	}

	/** Holder 统一归一化为注册名，避免本地解析与网络解码的 Holder 实例身份差异。 */
	private static String holderKey(final Holder<?> holder) {
		return holder.unwrapKey()
			.map(key -> key.identifier().toString())
			.orElseGet(() -> {
				Object value = holder.value();
				if (value instanceof Item item) {
					return BuiltInRegistries.ITEM.getKey(item).toString();
				}
				return String.valueOf(value);
			});
	}
}
