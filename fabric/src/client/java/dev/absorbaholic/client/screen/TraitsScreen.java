package dev.absorbaholic.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.absorbaholic.client.ClientState;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.net.TraitsPayload;
import dev.absorbaholic.net.WorldStatePayload;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceSummary;
import dev.absorbaholic.registry.SourceTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * The traits of one player: a scrollable list with the source icon, source name (tier color), trait and weakness
 * with levels, tier and the mutated / pure tags; hovering a row shows the descriptions. Entries whose source no
 * longer exists (datapack removed it) are greyed with their raw id. The header shows "N / max" and why traits are
 * dormant (mode OFF, creative / spectator, server without the mod). Opened by the K key, by
 * {@code /absorbaholic traits [player]} ({@link #open(TraitsPayload)}) and from the settings screen. Viewing your
 * own traits follows {@link ClientState#ownTraits()} live.
 */
public class TraitsScreen extends Screen {
	/** Row geometry (GUI pixels). */
	private static final int ROW_HEIGHT = 30;
	private static final int MAX_ROW_WIDTH = 340;
	private static final int ICON_X = 7;
	private static final int TEXT_X = 29;
	/** Text colors (ARGB: alpha is mandatory). */
	public static final int TRAIT_COLOR = 0xFF7EE07E;
	public static final int WEAKNESS_COLOR = 0xFFFF8A80;
	public static final int PURE_COLOR = 0xFF7FDBFF;
	public static final int MUTATED_COLOR = 0xFFE58CFF;
	public static final int MUTED_COLOR = 0xFFA0A0A0;
	public static final int DIM_COLOR = 0xFF707070;
	public static final int WARNING_COLOR = 0xFFFFD36B;

	private final @Nullable Screen parent;
	private final Component ownerName;
	private final boolean own;
	private PlayerTraits traits;
	private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private @Nullable TraitList list;

	/**
	 * @param ownerName whose traits these are (shown in the title unless {@code own})
	 * @param own       true for the viewer's own traits: the title says "My traits" and the list follows live updates
	 */
	public TraitsScreen(@Nullable Screen parent, Component ownerName, PlayerTraits traits, boolean own) {
		super(own ? Component.translatable("absorbaholic.screen.traits.title.own")
				: Component.translatable("absorbaholic.screen.traits.title", ownerName));
		this.parent = parent;
		this.ownerName = ownerName;
		this.traits = traits;
		this.own = own;
	}

	/** The local player's own traits ({@link ClientState#ownTraits()}). */
	public static TraitsScreen own(@Nullable Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		Component name = mc.player != null ? mc.player.getName() : Component.empty();
		return new TraitsScreen(parent, name, ClientState.ownTraits(), true);
	}

	/**
	 * Opens the screen for a {@code TraitsPayload} with {@code openScreen} (answer to {@code /absorbaholic traits}).
	 * Call on the render thread (payload handlers run there). Replaces any open screen; the chat screen that ran the
	 * command closes itself right after, so this is deferred by one task.
	 */
	public static void open(TraitsPayload payload) {
		Minecraft mc = Minecraft.getInstance();
		boolean own = mc.player != null && mc.player.getUUID().equals(payload.owner());
		mc.schedule(() -> mc.gui.setScreen(new TraitsScreen(null, Component.literal(payload.ownerName()), payload.traits(), own)));
	}

	/** Whose traits are shown. */
	public Component ownerName() {
		return ownerName;
	}

	/** The traits shown right now. */
	public PlayerTraits traits() {
		return traits;
	}

	/** Number of rows in the list (one per entry). */
	public int rowCount() {
		return list == null ? 0 : list.children().size();
	}

	@Override
	protected void init() {
		List<Component> status = statusLines();
		layout = new HeaderAndFooterLayout(this, 30 + 11 * status.size() + 6, 33);
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(2));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(this.title, this.font));
		header.addChild(new StringWidget(countLine(), this.font));
		for (Component line : status) {
			header.addChild(new StringWidget(line, this.font).setMaxWidth(this.width - 16));
		}
		list = layout.addToContents(new TraitList());
		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
		layout.visitWidgets(this::addRenderableWidget);
		list.fill(traits);
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		if (list != null) list.updateSize(this.width, layout);
	}

	@Override
	public void tick() {
		if (own && ClientState.ownTraits() != traits) {
			traits = ClientState.ownTraits();
			rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (traits.isEmpty() && list != null) {
			Component empty = Component.translatable(own ? "absorbaholic.screen.traits.empty.own" : "absorbaholic.screen.traits.empty");
			int maxWidth = Math.min(260, this.width - 40);
			List<FormattedCharSequence> lines = this.font.split(empty, maxWidth);
			int y = list.getY() + (list.getHeight() - lines.size() * 11) / 2;
			for (FormattedCharSequence line : lines) {
				graphics.centeredText(this.font, line, this.width / 2, y, MUTED_COLOR);
				y += 11;
			}
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	/** "N / max traits" (max only when the server told us). */
	private Component countLine() {
		WorldStatePayload world = ClientState.world();
		Component count = world != null
				? Component.translatable("absorbaholic.screen.traits.count", traits.size(), world.maxTraits())
				: Component.translatable("absorbaholic.screen.traits.count.unknown", traits.size());
		return count.copy().withColor(MUTED_COLOR);
	}

	/** Why the listed traits are dormant, if they are (warning color). */
	private List<Component> statusLines() {
		List<Component> lines = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && !ClientState.serverHasMod()) {
			lines.add(Component.translatable("absorbaholic.screen.traits.status.no_mod").withColor(WARNING_COLOR));
		} else if (mc.player != null && !ClientState.modeEnabled()) {
			lines.add(Component.translatable("absorbaholic.screen.traits.status.mode_off").withColor(WARNING_COLOR));
		} else if (own && mc.player != null && (mc.player.isCreative() || mc.player.isSpectator())) {
			lines.add(Component.translatable("absorbaholic.screen.traits.status.creative").withColor(WARNING_COLOR));
		}
		return lines;
	}

	// ---- shared source presentation (also used by the HUD hint) ----

	private static final Map<Identifier, ItemStack> ICONS = new HashMap<>();
	/** The source set the icon cache was built for (a new set after /reload or a reconnect drops the cache). */
	private static @Nullable Object iconsFor;

	/**
	 * Display name of a source: {@code absorbaholic.source.<path>} (or {@code <namespace>.source.<path>}) when the
	 * language has it, else the name of its first direct target, else the raw id.
	 */
	public static Component sourceName(SourceSummary source) {
		Identifier id = source.id();
		String key = id.getNamespace() + ".source." + id.getPath();
		if (Language.getInstance().has(key)) return Component.translatable(key);
		SourceTargets targets = source.targets();
		for (Identifier target : targets.ids()) {
			if (targets.kind() == SourceKind.BLOCK) {
				Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(target);
				if (block.isPresent()) return block.get().getName();
			} else {
				Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(target);
				if (type.isPresent()) return type.get().getDescription();
			}
		}
		return Component.literal(id.toString());
	}

	/** Trait name, plus the roman level when {@code level > 0}. */
	public static MutableComponent traitText(SourceSummary source, int level) {
		MutableComponent name = Component.translatable(source.traitLangKey());
		return level > 0 ? Component.translatable("absorbaholic.screen.name_level", name, roman(level)) : name;
	}

	/** Weakness name, plus the roman level when {@code level > 0}. */
	public static MutableComponent weaknessText(SourceSummary source, int level) {
		MutableComponent name = Component.translatable(source.weaknessLangKey());
		return level > 0 ? Component.translatable("absorbaholic.screen.name_level", name, roman(level)) : name;
	}

	/** Vanilla roman numeral ({@code enchantment.level.N}, I..X). */
	public static Component roman(int level) {
		return Component.translatable("enchantment.level." + level);
	}

	/** Text color of a tier, opaque. */
	public static int tierColor(Tier tier) {
		return 0xFF000000 | tier.color;
	}

	/**
	 * Icon of a source: its {@code icon} item, else the block item / spawn egg of its first target (direct ids first,
	 * then the first member of its first non-empty tag), a bucket for fluids, else a barrier / name tag. Cached per id
	 * until the synced source set changes. Render thread only.
	 */
	public static ItemStack icon(SourceSummary source) {
		if (iconsFor != ClientState.sources()) {
			ICONS.clear();
			iconsFor = ClientState.sources();
		}
		return ICONS.computeIfAbsent(source.id(), id -> computeIcon(source));
	}

	private static ItemStack computeIcon(SourceSummary source) {
		if (source.icon().isPresent()) {
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(source.icon().get());
			if (item.isPresent() && item.get() != Items.AIR) return new ItemStack(item.get());
		}
		SourceTargets targets = source.targets();
		if (targets.kind() == SourceKind.BLOCK) {
			for (Identifier id : targets.ids()) {
				Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
				if (block.isPresent()) return blockIcon(block.get());
			}
			for (Identifier tag : targets.tags()) {
				for (Holder<Block> block : BuiltInRegistries.BLOCK.getTagOrEmpty(TagKey.create(Registries.BLOCK, tag))) {
					ItemStack stack = blockIcon(block.value());
					if (!stack.is(Items.BARRIER)) return stack;
				}
			}
			return new ItemStack(Items.BARRIER);
		}
		for (Identifier id : targets.ids()) {
			Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
			if (type.isPresent()) {
				Optional<ItemStack> egg = SpawnEggItem.byId(type.get()).map(ItemStack::new);
				if (egg.isPresent()) return egg.get();
			}
		}
		for (Identifier tag : targets.tags()) {
			for (Holder<EntityType<?>> type : BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(TagKey.create(Registries.ENTITY_TYPE, tag))) {
				Optional<ItemStack> egg = SpawnEggItem.byId(type.value()).map(ItemStack::new);
				if (egg.isPresent()) return egg.get();
			}
		}
		return new ItemStack(Items.NAME_TAG);
	}

	private static ItemStack blockIcon(Block block) {
		Item item = block.asItem();
		if (item != Items.AIR) return new ItemStack(item);
		Item bucket = block.defaultBlockState().getFluidState().getType().getBucket();
		if (bucket != Items.AIR) return new ItemStack(bucket);
		return new ItemStack(Items.BARRIER);
	}

	/** Cuts {@code text} to {@code maxWidth}, ending with "…" when it had to cut. */
	public static FormattedCharSequence clip(Font font, Component text, int maxWidth) {
		if (maxWidth <= 0) return FormattedCharSequence.EMPTY;
		if (font.width(text) <= maxWidth) return text.getVisualOrderText();
		int ellipsis = font.width(CommonComponents.ELLIPSIS);
		FormattedText cut = font.substrByWidth(text, Math.max(0, maxWidth - ellipsis));
		return Language.getInstance().getVisualOrder(FormattedText.composite(cut, CommonComponents.ELLIPSIS));
	}

	/** The scrolling list of trait rows. */
	final class TraitList extends ObjectSelectionList<TraitRow> {
		TraitList() {
			super(TraitsScreen.this.minecraft, TraitsScreen.this.width, TraitsScreen.this.layout.getContentHeight(),
					TraitsScreen.this.layout.getHeaderHeight(), ROW_HEIGHT);
		}

		void fill(PlayerTraits shown) {
			List<TraitRow> rows = new ArrayList<>();
			for (TraitEntry entry : shown.entries()) rows.add(new TraitRow(entry, ClientState.sources().byId(entry.source()).orElse(null)));
			replaceEntries(rows);
		}

		@Override
		public int getRowWidth() {
			return Math.min(MAX_ROW_WIDTH, TraitsScreen.this.width - 32);
		}
	}

	/** One absorbed source. {@code source} is null when the server no longer has it (inactive, greyed). */
	final class TraitRow extends ObjectSelectionList.Entry<TraitRow> {
		private final TraitEntry entry;
		private final @Nullable SourceSummary source;
		private final Component name;
		private final ItemStack icon;

		TraitRow(TraitEntry entry, @Nullable SourceSummary source) {
			this.entry = entry;
			this.source = source;
			this.name = source != null ? sourceName(source) : Component.literal(entry.source().toString());
			this.icon = source != null ? icon(source) : new ItemStack(Items.BARRIER);
		}

		@Override
		public Component getNarration() {
			return Component.translatable("narrator.select", name);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			Font font = TraitsScreen.this.font;
			int x = getContentX();
			int y = getContentY();
			int w = getContentWidth();
			int h = getContentHeight();
			boolean active = source != null;
			int accent = active ? tierColor(source.tier()) : DIM_COLOR;

			graphics.fill(x, y, x + w, y + h, hovered ? 0x40FFFFFF : 0x30000000);
			graphics.fill(x, y, x + 2, y + h, accent);
			graphics.fakeItem(icon, x + ICON_X, y + (h - 16) / 2);
			if (!active) graphics.fill(x + ICON_X, y + (h - 16) / 2, x + ICON_X + 16, y + (h - 16) / 2 + 16, 0x90303030);

			int textX = x + TEXT_X;
			int right = x + w - 5;
			int line1 = y + (h - 20) / 2 + 1;
			int line2 = line1 + 11;

			// line 1: name (tier color) ... tier label
			Component tierLabel = active ? Component.translatable(source.tier().langKey()) : Component.translatable("absorbaholic.screen.traits.removed");
			int tierWidth = font.width(tierLabel);
			graphics.text(font, tierLabel, right - tierWidth, line1, active ? dim(accent) : DIM_COLOR);
			graphics.text(font, clip(font, name, right - tierWidth - 8 - textX), textX, line1, active ? accent : MUTED_COLOR);

			// line 2: trait · weakness ... tags
			int tagsX = right;
			if (entry.pure()) tagsX = tag(graphics, font, "absorbaholic.screen.traits.tag.pure", tagsX, line2, active ? PURE_COLOR : DIM_COLOR);
			if (entry.mutated()) tagsX = tag(graphics, font, "absorbaholic.screen.traits.tag.mutated", tagsX, line2, active ? MUTATED_COLOR : DIM_COLOR);
			int avail = tagsX - 6 - textX;
			if (!active) {
				graphics.text(font, clip(font, Component.translatable("absorbaholic.screen.traits.inactive"), avail), textX, line2, DIM_COLOR);
				return;
			}
			Component trait = traitText(source, entry.traitLevel());
			Component weakness = entry.weaknessLevel() > 0
					? weaknessText(source, entry.weaknessLevel())
					: Component.translatable("absorbaholic.screen.traits.no_weakness");
			int weaknessColor = entry.weaknessLevel() > 0 ? WEAKNESS_COLOR : PURE_COLOR;
			int sep = font.width(" · ");
			int traitW = font.width(trait);
			int weaknessW = font.width(weakness);
			if (traitW + sep + weaknessW > avail) {
				int half = (avail - sep) / 2;
				if (traitW < half) {
					weaknessW = avail - sep - traitW;
				} else if (weaknessW < half) {
					traitW = avail - sep - weaknessW;
				} else {
					traitW = half;
					weaknessW = avail - sep - half;
				}
			}
			graphics.text(font, clip(font, trait, traitW), textX, line2, TRAIT_COLOR);
			int wx = textX + Math.min(traitW, font.width(trait));
			graphics.text(font, " · ", wx, line2, DIM_COLOR);
			graphics.text(font, clip(font, weakness, weaknessW), wx + sep, line2, weaknessColor);

			if (hovered) graphics.setTooltipForNextFrame(font, tooltip(font), mouseX, mouseY);
		}

		/** Draws a tag right-aligned to {@code right}; returns the x where the next tag must end. */
		private int tag(GuiGraphicsExtractor graphics, Font font, String key, int right, int y, int color) {
			Component text = Component.translatable(key);
			int width = font.width(text);
			graphics.text(font, text, right - width, y, color);
			return right - width - 6;
		}

		private List<FormattedCharSequence> tooltip(Font font) {
			SourceSummary s = source;
			List<FormattedCharSequence> lines = new ArrayList<>();
			int wrap = Math.max(120, Math.min(220, TraitsScreen.this.width / 2));
			lines.add(Component.empty().append(name).withColor(tierColor(s.tier())).getVisualOrderText());
			lines.add(Component.translatable("absorbaholic.screen.traits.tooltip.trait", traitText(s, entry.traitLevel()), roman(s.maxLevel()))
					.withColor(TRAIT_COLOR).getVisualOrderText());
			lines.addAll(font.split(Component.translatable(s.traitLangKey() + ".desc").withColor(MUTED_COLOR), wrap));
			if (entry.weaknessLevel() > 0) {
				lines.add(Component.translatable("absorbaholic.screen.traits.tooltip.weakness", weaknessText(s, entry.weaknessLevel()))
						.withColor(WEAKNESS_COLOR).getVisualOrderText());
			} else {
				lines.add(Component.translatable("absorbaholic.screen.traits.tooltip.weakness_inactive", weaknessText(s, 0))
						.withColor(PURE_COLOR).getVisualOrderText());
			}
			lines.addAll(font.split(Component.translatable(s.weaknessLangKey() + ".desc").withColor(MUTED_COLOR), wrap));
			if (entry.mutated()) lines.addAll(font.split(Component.translatable("absorbaholic.screen.traits.tooltip.mutated").withColor(MUTATED_COLOR), wrap));
			if (entry.pure()) lines.addAll(font.split(Component.translatable("absorbaholic.screen.traits.tooltip.pure").withColor(PURE_COLOR), wrap));
			if (TraitsScreen.this.minecraft.options.advancedItemTooltips) lines.add(Component.literal(s.id().toString()).withColor(DIM_COLOR).getVisualOrderText());
			return lines;
		}
	}

	/** A darker shade of a text color (for secondary labels in a tier color). */
	private static int dim(int argb) {
		return 0xFF000000 | (((argb >> 1) & 0x7F7F7F) + 0x303030);
	}
}
