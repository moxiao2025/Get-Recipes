package com.recipe.client;

import com.recipe.GetRecipes;
import com.recipe.mixin.client.ClientRecipeBookAccessor;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
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
import java.util.TreeSet;

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
	/** 与 {@link #entries} 一一对应的预计算严格签名（在 IO 线程生成）。 */
	private static volatile List<String> entrySignatures = List.of();
	/** 与 {@link #entries} 一一对应的预计算宽松身份签名（服务端变体兜底，IO 线程生成）。 */
	private static volatile List<String> entryLooseSignatures = List.of();
	/** 与 {@link #entries} 一一对应的预计算粗身份签名（服务端魔改材料兜底，IO 线程生成）。 */
	private static volatile List<String> entryCoarseSignatures = List.of();

	/**
	 * 服务器真实条目的三层签名缓存（主线程访问）。
	 * mergeInto 随每次 rebuildCollections 触发（初始同步、每个增量配方包、界面刷新），
	 * 而签名构建依赖反射，必须避免对同一条目重复计算：
	 * 键为服务器 display ID，值同时记录条目引用，条目被替换时自动失效。
	 * 每次连接在 onJoin 中清空。
	 */
	private record ServerSigs(RecipeDisplayEntry entry, String strict, String loose, String coarse) {
	}

	private static final Map<RecipeDisplayId, ServerSigs> serverSigCache = new java.util.HashMap<>();

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
		entryLooseSignatures = List.of();
		entryCoarseSignatures = List.of();
		serverSigCache.clear();

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
			List<String> loadedLooseSignatures = List.of();
			List<String> loadedCoarseSignatures = List.of();
			try {
				GetRecipesLocalLoader.LoadResult load =
					GetRecipesLocalLoader.loadSilently(dataResources, registries, features);
				GetRecipes.LOGGER.info(
					"GetRecipes: parsed {} local recipe(s) from {} JSON file(s) ({} skipped: missing items/tags this server does not have)",
					load.loaded(), load.found(), load.failed()
				);

				List<RecipeManager.ServerDisplayInfo> displays = load.displays();
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
				loadedLooseSignatures = result.stream().map(LocalRecipeUnlocker::looseSignature).toList();
				loadedCoarseSignatures = result.stream().map(LocalRecipeUnlocker::coarseSignature).toList();
				GetRecipes.LOGGER.info("GetRecipes: loaded {} recipe displays from local data packs", result.size());
			} catch (RuntimeException e) {
				GetRecipes.LOGGER.error("GetRecipes: failed to load local recipes for the client recipe book", e);
			}

			final List<RecipeDisplayEntry> finalLoaded = loaded;
			final List<String> finalSignatures = loadedSignatures;
			final List<String> finalLooseSignatures = loadedLooseSignatures;
			final List<String> finalCoarseSignatures = loadedCoarseSignatures;
			client.execute(() -> {
				entries = finalLoaded;
				entrySignatures = finalSignatures;
				entryLooseSignatures = finalLooseSignatures;
				entryCoarseSignatures = finalCoarseSignatures;
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
	 * 将本地配方合并进指定的客户端配方书。
	 * 每次重建时做一次完整对账：
	 * <ul>
	 *   <li>移除"已被服务器真实条目覆盖"的本地幽灵副本——处理进服后服务器增量解锁
	 *       （ClientboundRecipeBookAddPacket, replace=false）带来的重复；</li>
	 *   <li>补入服务器仍未解锁的本地配方。</li>
	 * </ul>
	 * 匹配分三层：
	 * <ol>
	 *   <li>严格签名（完整结构相等）；</li>
	 *   <li>宽松身份签名（分类 + 工作台 + 结果物 + 材料多重集合，忽略形状/槽位包装/顺序），
	 *       兼容服务端把 ItemSlotDisplay 统一生成为 ItemStackSlotDisplay(count=1)、
	 *       把物品标签提前展开为枚举等差异（如 CraftEngine）；</li>
	 *   <li>粗身份签名（分类 + 展示类型 + 工作台 + 完整结果物 + 非空材料槽数，忽略材料身份），
	 *       兼容服务端深度魔改配方材料（如空岛服把木质配方的木板/木棍/碗等统一替换为
	 *       #minecraft:planks 标签）：产物与槽数相同即视为同一条配方，以服务器真实条目为准。
	 *       结果物的数据组件仍参与区分，避免误删不同效果的可疑炖菜等"同物品不同内容"配方。</li>
	 * </ol>
	 * 在客户端主线程随 ClientRecipeBook#rebuildCollections 一起调用。
	 */
	public static void mergeInto(final ClientRecipeBook book) {
		List<RecipeDisplayEntry> local = entries;
		List<String> localSigs = entrySignatures;
		List<String> localLooseSigs = entryLooseSignatures;
		List<String> localCoarseSigs = entryCoarseSignatures;
		if (local.isEmpty() || localSigs.isEmpty() || Minecraft.getInstance().getSingleplayerServer() != null) {
			return;
		}

		Map<RecipeDisplayId, RecipeDisplayEntry> known =
			((ClientRecipeBookAccessor) book).getrecipes$getKnown();

		// 1. 收集全部已知条目的签名。
		//    本地条目直接使用 IO 线程预计算结果；服务器条目按 ID 取缓存，
		//    只有新出现或被替换的条目才做一次反射签名计算。
		Set<String> serverStrict = new HashSet<>(known.size() * 2);
		Set<String> serverLoose = new HashSet<>(known.size() * 2);
		Set<String> serverCoarse = new HashSet<>(known.size() * 2);
		Set<String> existingStrict = new HashSet<>(known.size() * 2);
		for (RecipeDisplayEntry entry : known.values()) {
			int localIndex = entry.id().index() - LOCAL_RECIPE_ID_BASE;
			if (localIndex >= 0 && localIndex < localSigs.size()) {
				existingStrict.add(localSigs.get(localIndex));
				continue;
			}
			ServerSigs sigs = serverSigCache.get(entry.id());
			if (sigs == null || sigs.entry() != entry) {
				sigs = new ServerSigs(entry, semanticSignature(entry), looseSignature(entry), coarseSignature(entry));
				serverSigCache.put(entry.id(), sigs);
			}
			serverStrict.add(sigs.strict());
			serverLoose.add(sigs.loose());
			serverCoarse.add(sigs.coarse());
			existingStrict.add(sigs.strict());
		}

		// 2. 移除已被服务器真实条目覆盖的本地幽灵条目
		//    （本地 ID 为 LOCAL_RECIPE_ID_BASE + 在 entries 中的下标，可直接定位预计算签名）
		int removedStrict = 0;
		int removedLoose = 0;
		int removedCoarse = 0;
		var it = known.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<RecipeDisplayId, RecipeDisplayEntry> kv = it.next();
			int localIndex = kv.getKey().index() - LOCAL_RECIPE_ID_BASE;
			if (localIndex < 0 || localIndex >= localSigs.size()) {
				continue;
			}
			if (serverStrict.contains(localSigs.get(localIndex))) {
				it.remove();
				removedStrict++;
			} else if (serverLoose.contains(localLooseSigs.get(localIndex))) {
				it.remove();
				removedLoose++;
			} else if (serverCoarse.contains(localCoarseSigs.get(localIndex))) {
				it.remove();
				removedCoarse++;
			}
		}

		// 3. 按语义内容补入服务器仍未解锁的本地配方（existingStrict 已在第 1 步构建）
		int added = 0;
		for (int i = 0; i < local.size(); i++) {
			RecipeDisplayEntry entry = local.get(i);
			// ID 冲突防御 + 三层签名去重（服务器也装了本 mod、协议翻译或深度魔改材料时均能命中）
			if (known.containsKey(entry.id())
				|| existingStrict.contains(localSigs.get(i))
				|| serverLoose.contains(localLooseSigs.get(i))
				|| serverCoarse.contains(localCoarseSigs.get(i))) {
				continue;
			}
			book.add(entry);
			existingStrict.add(localSigs.get(i));
			added++;
		}
		int removed = removedStrict + removedLoose + removedCoarse;
		if (removed > 0 || added > 0) {
			GetRecipes.LOGGER.info(
				"GetRecipes: reconciled local recipes ({} added, {} ghost cop(ies) replaced by server-unlocked entries, {} via loose identity, {} via coarse identity)",
				added, removed, removedLoose, removedCoarse
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
		// mergeInto 已由 ClientRecipeBookMixin 注入在 rebuildCollections HEAD 执行，无需手动调用
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
			return sb.append("tag{").append(tagIdentity(tagOf(d))).append('}');
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

	/** TagSlotDisplay#tag() 访问器反射缓存（26.1 返回 TagKey，26.3 返回 HolderSet）。 */
	private static volatile java.lang.reflect.Method tagSlotAccessor;

	/**
	 * 反射读取 TagSlotDisplay 的 tag 组件。
	 * 不能直接调用 d.tag()：方法描述符含返回类型，26.1 编译出的字节码在 26.3
	 * （返回类型改为 HolderSet）上会抛 NoSuchMethodError。
	 */
	private static Object tagOf(final SlotDisplay.TagSlotDisplay display) {
		try {
			java.lang.reflect.Method accessor = tagSlotAccessor;
			if (accessor == null) {
				accessor = SlotDisplay.TagSlotDisplay.class.getMethod("tag");
				tagSlotAccessor = accessor;
			}
			return accessor.invoke(display);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("GetRecipes: TagSlotDisplay#tag unavailable", e);
		}
	}

	/**
	 * TagSlotDisplay 的标签身份。26.1/26.2 记录 TagKey，26.3 起改为直接记录
	 * HolderSet（网络侧同标签的 Named 集合取其标签键；无标签的 Direct 集合
	 * 退化为成员列表）。
	 */
	private static String tagIdentity(final Object tag) {
		if (tag instanceof TagKey<?> key) {
			return key.location().toString();
		}
		if (tag instanceof HolderSet<?> set) {
			return set.unwrapKey()
				.map(key -> key.location().toString())
				.orElseGet(() -> {
					StringBuilder members = new StringBuilder("direct[");
					boolean first = true;
					for (Object element : (Iterable<?>) set) {
						if (!first) {
							members.append(',');
						}
						first = false;
						members.append(element instanceof Holder<?> holder ? holderKey(holder) : String.valueOf(element));
					}
					return members.append(']').toString();
				});
		}
		return String.valueOf(tag);
	}

	// ----------------------------------------------------------------------------------
	// 宽松身份签名
	//
	// 用于跨协议翻译场景（如 ViaFabricPlus 接入旧版服务器）：旧协议的配方经翻译重建为
	// RecipeDisplayEntry 时，槽位包装方式（单个物品是否包一层 Composite）、有序/无序、
	// 槽位顺序等表现形式可能与本地解析结果不同，但"配方身份"不变。
	//
	// 身份仅取：分类 + 工作台 + 结果物 + 材料多重集合（每个槽位是一组"或"选项，
	// 槽位之间不计顺序）。不同产物 / 不同工作台 / 不同材料集合的配方仍然可区分。
	// ----------------------------------------------------------------------------------

	private static String looseSignature(final RecipeDisplayEntry entry) {
		RecipeDisplay display = entry.display();
		Set<String> resultTokens = new TreeSet<>();
		Set<String> stationTokens = new TreeSet<>();
		List<Set<String>> ingredientSlots = new ArrayList<>();

		if (display.getClass().isRecord()) {
			for (RecordComponent component : display.getClass().getRecordComponents()) {
				Object value;
				try {
					value = component.getAccessor().invoke(display);
				} catch (ReflectiveOperationException e) {
					continue;
				}
				String name = component.getName();
				if (value instanceof SlotDisplay slotDisplay) {
					Set<String> tokens = flattenSlot(slotDisplay);
					if ("result".equals(name)) {
						resultTokens.addAll(tokens);
					} else if ("craftingStation".equals(name)) {
						stationTokens.addAll(tokens);
					} else {
						// 其余槽位（ingredient/input/template/base/addition/fuel 等）一律视为材料
						ingredientSlots.add(tokens);
					}
				} else if (value instanceof List<?> list
					&& (list.isEmpty() || list.get(0) instanceof SlotDisplay)) {
					for (Object element : list) {
						ingredientSlots.add(flattenSlot((SlotDisplay) element));
					}
				}
			}
		}

		// 每个槽位内部排序（或选项无序），槽位之间再排序（忽略摆放位置/顺序）
		List<String> slotKeys = new ArrayList<>(ingredientSlots.size());
		for (Set<String> slot : ingredientSlots) {
			slotKeys.add(String.join("|", slot));
		}
		slotKeys.sort(null);

		Object categoryName = BuiltInRegistries.RECIPE_BOOK_CATEGORY.getKey(entry.category());
		return "cat=" + categoryName
			+ "|st=" + String.join("|", stationTokens)
			+ "|res=" + String.join("|", resultTokens)
			+ "|ing=" + String.join(";;", slotKeys);
	}

	// ----------------------------------------------------------------------------------
	// 粗身份签名
	//
	// 用于服务端深度魔改配方的场景（已确认：空岛服）：
	// 1) 材料被统一替换为 #minecraft:planks / #minecraft:mushrooms 等标签（木板/木棍/碗/花）；
	// 2) craftingStation 一律清空（背包合成格也允许）；
	// 3) 配方书分类被整体改塞（烧炼配方显示为 crafting_redstone/crafting_equipment）；
	// 4) 服务端 ViaVersion 给工具产物注入 custom_data 翻译噪声。
	//
	// 身份取：展示类型 + 完整结果物（物品 + 数量 + 清洗后的非噪声数据组件）
	//        + 非空材料槽数 + 其余原始字段（width/height/duration/experience）。
	// 不含分类与工作台（上述 2、3 两类服务端改动），材料身份忽略（第 1 类）。
	// 数据组件仍保留（仅清洗 Via 噪声）：不同效果的可疑炖菜、插件自定义物品不会误合并。
	// ----------------------------------------------------------------------------------

	/** custom_data 中的跨版本翻译噪声键：Damage 与 ViaVersion 的 "VV|Protocol*" 临时标记。 */
	private static final java.util.regex.Pattern VIA_CUSTOM_DATA_NOISE =
		java.util.regex.Pattern.compile("(?:\"?VV\\|Protocol[^\"]*\"?|Damage)\\s*:\\s*[^,}]+,?\\s*");

	private static String coarseSignature(final RecipeDisplayEntry entry) {
		RecipeDisplay display = entry.display();
		Set<String> resultTokens = new TreeSet<>();
		int nonEmptySlots = 0;
		StringBuilder raw = new StringBuilder();

		if (display.getClass().isRecord()) {
			for (RecordComponent component : display.getClass().getRecordComponents()) {
				Object value;
				try {
					value = component.getAccessor().invoke(display);
				} catch (ReflectiveOperationException e) {
					continue;
				}
				String name = component.getName();
				if ("craftingStation".equals(name)) {
					// 服务端可能把工作台清空（任意合成格可做），不参与身份
					continue;
				}
				if (value instanceof SlotDisplay slotDisplay) {
					Set<String> tokens = flattenSlotCoarse(slotDisplay);
					if ("result".equals(name)) {
						resultTokens.addAll(tokens);
					} else if (!tokens.isEmpty()) {
						// ingredient/input/template/base/addition/fuel 等非空单槽，只计槽数
						nonEmptySlots++;
					}
				} else if (value instanceof List<?> list
					&& (list.isEmpty() || list.get(0) instanceof SlotDisplay)) {
					for (Object element : list) {
						// Shaped/Shapeless 的材料列表：空槽（Empty 或展平后无 token）不计入
						if (!flattenSlotCoarse((SlotDisplay) element).isEmpty()) {
							nonEmptySlots++;
						}
					}
				} else if (value != null && !(value instanceof Optional<?> optional && optional.isEmpty())) {
					// width/height/duration/experience 等原始字段保留，
					// 用于区分同类型同产物同槽数但工艺参数不同的配方（如熔炉/烟熏炉）
					raw.append('|').append(name).append('=').append(value);
				}
			}
		}

		return "type=" + display.getClass().getSimpleName()
			+ "|res=" + String.join("|", resultTokens)
			+ "|slots=" + nonEmptySlots
			+ raw;
	}

	/**
	 * 粗签名用的组件哈希：清洗 custom_data 中的 ViaVersion 翻译噪声键后计算。
	 * 清洗后无任何组件则返回空串（与"无组件"等价）。
	 */
	private static String sanitizedComponentHash(final DataComponentPatch components) {
		if (components.size() == 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<?, ?> kv : patchEntries(components)) {
			String keyName = String.valueOf(
				BuiltInRegistries.DATA_COMPONENT_TYPE.getKey((DataComponentType<?>) kv.getKey())
			);
			Object raw = kv.getValue();
			// 26.1/26.2 的 patch 值为 Optional（empty 表示删除组件），26.3 起为原始组件值
			String valueText = raw instanceof Optional<?> optional
				? optional.map(String::valueOf).orElse("removed")
				: String.valueOf(raw);
			if ("minecraft:custom_data".equals(keyName)) {
				valueText = VIA_CUSTOM_DATA_NOISE.matcher(valueText).replaceAll("");
				if ("{}".equals(valueText.trim())) {
					continue;
				}
			}
			sb.append(keyName).append('=').append(valueText).append(';');
		}
		return sb.length() == 0 ? "" : Integer.toHexString(sb.toString().hashCode());
	}

	/**
	 * 遍历 DataComponentPatch 的 (组件类型, 值) 条目。
	 * 26.1/26.2 提供公开的 entrySet()；26.3 起移除，仅保留包私有字段 map
	 * （fastutil Reference2ObjectMap，两版本字段名一致），统一走反射读取。
	 */
	private static List<Map.Entry<?, ?>> patchEntries(final DataComponentPatch components) {
		try {
			java.lang.reflect.Field field = DataComponentPatch.class.getDeclaredField("map");
			field.setAccessible(true);
			return new ArrayList<>(((Map<?, ?>) field.get(components)).entrySet());
		} catch (ReflectiveOperationException e) {
			return List.of();
		}
	}

	/**
	 * 将一个槽位展开为一组物品/标签 token（槽位内是"或"关系）。
	 * token 形式：i:物品id（可带 *数量）、t:标签id、#燃料、p:纹饰id。
	 */
	private static Set<String> flattenSlot(final SlotDisplay display) {
		Set<String> tokens = new TreeSet<>();
		flattenSlot(display, tokens, false);
		return tokens;
	}

	/**
	 * 粗签名专用展开：产物组件中的跨版本翻译噪声（ViaVersion 注入的
	 * custom_data 键 Damage / "VV|Protocol*"）会被清洗掉，
	 * 其余 custom_data 内容仍保留（服务端插件常用它伪装自定义物品）。
	 */
	private static Set<String> flattenSlotCoarse(final SlotDisplay display) {
		Set<String> tokens = new TreeSet<>();
		flattenSlot(display, tokens, true);
		return tokens;
	}

	private static void flattenSlot(final SlotDisplay display, final Set<String> tokens, final boolean coarse) {
		if (display == null || display instanceof SlotDisplay.Empty) {
			return;
		}
		if (display instanceof SlotDisplay.AnyFuel) {
			tokens.add("#fuel");
			return;
		}
		if (display instanceof SlotDisplay.ItemSlotDisplay d) {
			tokens.add("i:" + holderKey(d.item()));
			return;
		}
		if (display instanceof SlotDisplay.TagSlotDisplay d) {
			// 归一化：把标签展开为成员物品，使"标签槽"与服务端"显式枚举同标签成员"的
			// Composite 槽视为同一槽位（部分服务端会提前把标签展开成具体物品列表）。
			// 26.1/26.2 记录 TagKey，26.3 起改为直接记录 HolderSet，按运行期类型解包。
			// 必须反射取该访问器：26.1 编译出的字节码描述符写死返回 TagKey，
			// 在 26.3 上直接调用会因描述符不匹配抛 NoSuchMethodError。
			Object tag = tagOf(d);
			int before = tokens.size();
			if (tag instanceof TagKey<?> key) {
				BuiltInRegistries.ITEM.getTagOrEmpty((TagKey<Item>) key)
					.forEach(holder -> tokens.add("i:" + holderKey(holder)));
				if (tokens.size() == before) {
					tokens.add("t:" + key.location());
				}
			} else if (tag instanceof HolderSet<?> set) {
				for (Object element : (Iterable<?>) set) {
					if (element instanceof Holder<?> holder) {
						tokens.add("i:" + holderKey(holder));
					}
				}
				if (tokens.size() == before) {
					set.unwrapKey().ifPresent(key -> tokens.add("t:" + key.location()));
				}
			}
			return;
		}
		if (display instanceof SlotDisplay.ItemStackSlotDisplay d) {
			ItemStackTemplate stack = d.stack();
			// count=1 时省略数量，与 ItemSlotDisplay 归一化（部分服务端把所有材料槽
			// 统一生成为 ItemStackSlotDisplay(count=1)，而本地数据包解析为 ItemSlotDisplay）
			String token = "i:" + holderKey(stack.item());
			if (stack.count() != 1) {
				token += "*" + stack.count();
			}
			// 非空组件必须参与身份：部分服务端用原版物品 + custom_data 伪装自定义物品，
			// 若忽略组件会与本地同基础物品的原版配方误合并。
			// 粗签名例外：清洗 ViaVersion 协议翻译注入的 custom_data 噪声键
			// （Damage、"VV|Protocol*"），清洗后为空则该组件不参与身份。
			String componentHash = coarse
				? sanitizedComponentHash(stack.components())
				: (stack.components().size() > 0 ? Integer.toHexString(stack.components().toString().hashCode()) : null);
			if (componentHash != null && !componentHash.isEmpty()) {
				token += "@" + componentHash;
			}
			tokens.add(token);
			return;
		}
		if (display instanceof SlotDisplay.Composite d) {
			for (SlotDisplay content : d.contents()) {
				flattenSlot(content, tokens, coarse);
			}
			return;
		}
		if (display instanceof SlotDisplay.WithRemainder d) {
			// 容器残留物（如桶）不参与配方身份
			flattenSlot(d.input(), tokens, coarse);
			return;
		}
		if (display instanceof SlotDisplay.WithAnyPotion d) {
			flattenSlot(d.display(), tokens, coarse);
			return;
		}
		if (display instanceof SlotDisplay.OnlyWithComponent d) {
			flattenSlot(d.source(), tokens, coarse);
			tokens.add("@" + BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(d.component()));
			return;
		}
		if (display instanceof SlotDisplay.DyedSlotDemo d) {
			flattenSlot(d.target(), tokens, coarse);
			flattenSlot(d.dye(), tokens, coarse);
			return;
		}
		if (display instanceof SlotDisplay.SmithingTrimDemoSlotDisplay d) {
			flattenSlot(d.base(), tokens, coarse);
			flattenSlot(d.material(), tokens, coarse);
			tokens.add("p:" + holderKey(d.pattern()));
			return;
		}
		// 未知槽位类型（新版本）：反射展开其中的 SlotDisplay / Holder 组件，尽量保持兼容
		if (display.getClass().isRecord()) {
			for (RecordComponent component : display.getClass().getRecordComponents()) {
				try {
					Object value = component.getAccessor().invoke(display);
					if (value instanceof SlotDisplay slotDisplay) {
						flattenSlot(slotDisplay, tokens, coarse);
					} else if (value instanceof Holder<?> holder) {
						tokens.add("h:" + holderKey(holder));
					}
				} catch (ReflectiveOperationException ignored) {
					// 无法访问的组件忽略
				}
			}
		}
	}
}
