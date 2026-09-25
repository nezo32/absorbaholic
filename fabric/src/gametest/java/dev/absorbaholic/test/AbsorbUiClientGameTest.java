package dev.absorbaholic.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import dev.absorbaholic.client.ClientState;
import dev.absorbaholic.client.hud.AbsorbHud;
import dev.absorbaholic.client.notify.NotifyConfig;
import dev.absorbaholic.client.screen.SettingsScreen;
import dev.absorbaholic.client.screen.TraitsKeybind;
import dev.absorbaholic.client.screen.TraitsScreen;
import dev.absorbaholic.core.NotifySettings;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.net.AuraPayload;
import dev.absorbaholic.net.ChannelStatePayload;
import dev.absorbaholic.net.TraitsPayload;
import dev.absorbaholic.net.WorldStatePayload;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceSummary;
import dev.absorbaholic.registry.SourceTargets;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import org.jspecify.annotations.Nullable;

/**
 * WP-UI client gametest (run with {@code runClientGameTest} under Xvfb). Client state is injected directly into
 * {@link ClientState} (sources, world state, discovered set, own traits, channel), so this test only depends on the UI:
 * <ol>
 * <li>Settings screen outside a world: both toggles save, "My traits" is inactive.</li>
 * <li>HUD hint in a flat world while sneaking: undiscovered ({@code ???}), discovered (trait / weakness names),
 *     not absorbable, a healthy mob ("weaken it"), the same mob weakened (no requirement line), protected bedrock,
 *     hints off and mode off (no hint), a full hand; the channel ring progress.</li>
 * <li>Traits screen: opened with the K key mapping, one row per entry, "N / max" header; opened for another player
 *     from a {@link TraitsPayload}; follows own-trait updates live.</li>
 * <li>Settings screen in a world: "My traits" is active and opens the traits screen.</li>
 * <li>Everything again in ru_ru, and at GUI scale 4 on a 1920×1080 window (screenshots for the layout review).</li>
 * </ol>
 */
public class AbsorbUiClientGameTest implements FabricClientGameTest {
	private static final Identifier OBSIDIAN = Identifier.fromNamespaceAndPath("absorbaholic", "obsidian");
	private static final Identifier LAVA = Identifier.fromNamespaceAndPath("absorbaholic", "lava");
	private static final Identifier COW = Identifier.fromNamespaceAndPath("absorbaholic", "cow");
	private static final Identifier BEDROCK = Identifier.fromNamespaceAndPath("absorbaholic", "bedrock");
	private static final Identifier LEAVES = Identifier.fromNamespaceAndPath("absorbaholic", "leaves");
	private static final Identifier GONE = Identifier.fromNamespaceAndPath("absorbaholic", "removed_by_pack");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		NotifySettings original = NotifyConfig.get();
		try {
			settingsOutsideWorld(ctx);
			try (TestSingleplayerContext sp = ctx.worldBuilder()
					.adjustSettings(s -> {
						s.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
						s.setAllowCommands(true);
					})
					.create()) {
				ctx.waitFor(mc -> mc.player != null, 20 * 60);
				sp.getConnection().waitForChunksRender();
				sp.getServer().runCommand("time set noon");
				sp.getServer().runCommand("gamerule send_command_feedback false"); // keep chat off the screenshots
				ctx.waitTicks(20);
				injectState(ctx);

				hud(ctx, sp, "en");
				aura(ctx);
				traitsScreen(ctx, "en");
				settingsInWorld(ctx, "en");

				setLanguage(ctx, "ru_ru");
				injectState(ctx);
				hud(ctx, sp, "ru");
				traitsScreen(ctx, "ru");
				settingsInWorld(ctx, "ru");

				guiScale4(ctx);
				setLanguage(ctx, "en_us");
			}
		} finally {
			NotifyConfig.set(original);
		}
		System.out.println("ABSORBAHOLIC_UI_CLIENT_TEST_OK");
	}

	// ---- settings (Mod Menu screen) ----

	private static void settingsOutsideWorld(ClientGameTestContext ctx) {
		NotifyConfig.set(NotifySettings.DEFAULT);
		ctx.setScreen(() -> new SettingsScreen(new TitleScreen()));
		ctx.waitForScreen(SettingsScreen.class);
		AbstractWidget traits = widget(ctx, SettingsScreen.TRAITS);
		if (traits.active) throw new AssertionError("'My traits' must be inactive outside a world");
		ctx.clickScreenButton(SettingsScreen.SOUND);
		expectSettings(new NotifySettings(false, true), "after sound click");
		ctx.clickScreenButton(SettingsScreen.MESSAGE);
		expectSettings(new NotifySettings(false, false), "after message click");
		ctx.takeScreenshot("ui_settings_title_off_en");
		ctx.clickScreenButton(SettingsScreen.SOUND);
		ctx.clickScreenButton(SettingsScreen.MESSAGE);
		expectSettings(NotifySettings.DEFAULT, "after clicking both again");
		ctx.clickScreenButton("gui.done");
		ctx.waitForScreen(TitleScreen.class);
	}

	private static void expectSettings(NotifySettings expected, String what) {
		if (!NotifyConfig.get().equals(expected)) throw new AssertionError(what + ": memory " + NotifyConfig.get());
		NotifySettings onDisk = NotifySettings.load(NotifyConfig.path());
		if (!onDisk.equals(expected)) throw new AssertionError(what + ": file " + onDisk + ", expected " + expected);
	}

	private static void settingsInWorld(ClientGameTestContext ctx, String lang) {
		ctx.setScreen(() -> new SettingsScreen(null));
		ctx.waitForScreen(SettingsScreen.class);
		AbstractWidget traits = widget(ctx, SettingsScreen.TRAITS);
		if (!traits.active) throw new AssertionError("'My traits' must be active in a world");
		ctx.takeScreenshot("ui_settings_world_" + lang);
		ctx.clickScreenButton(SettingsScreen.TRAITS);
		ctx.waitForScreen(TraitsScreen.class);
		ctx.clickScreenButton("gui.done");
		ctx.waitForScreen(SettingsScreen.class);
		ctx.clickScreenButton("gui.done");
		ctx.waitForScreen(null);
	}

	// ---- injected client state ----

	private static SourceSummary block(Identifier id, @Nullable String target, @Nullable String tag, @Nullable String icon, int color, Tier tier,
			String trait, String weakness) {
		List<Identifier> ids = target == null ? List.of() : List.of(Identifier.parse(target));
		List<Identifier> tags = tag == null ? List.of() : List.of(Identifier.parse(tag));
		return new SourceSummary(id, new SourceTargets(SourceKind.BLOCK, ids, tags),
				icon == null ? Optional.empty() : Optional.of(Identifier.parse(icon)), color, tier, 3, trait, weakness);
	}

	private static List<SourceSummary> sources() {
		List<SourceSummary> list = new ArrayList<>();
		list.add(block(OBSIDIAN, "minecraft:obsidian", null, null, 0x3B2754, Tier.RARE, "blast_proof", "dense"));
		list.add(block(LAVA, "minecraft:lava", null, "minecraft:lava_bucket", 0xE8601C, Tier.EPIC, "magma_blood", "hydrophobic"));
		list.add(block(BEDROCK, "minecraft:bedrock", null, null, 0x565656, Tier.EPIC, "immovable", "leaden_legs"));
		list.add(block(LEAVES, null, "minecraft:leaves", null, 0x4A8F28, Tier.COMMON, "photosynthesis", "light_as_a_leaf"));
		list.add(new SourceSummary(COW, new SourceTargets(SourceKind.ENTITY, List.of(Identifier.parse("minecraft:cow")), List.of()),
				Optional.empty(), 0x443626, Tier.COMMON, 3, "milk_drinker", "herbivore"));
		list.add(new SourceSummary(Identifier.fromNamespaceAndPath("absorbaholic", "ender_dragon"),
				new SourceTargets(SourceKind.ENTITY, List.of(Identifier.parse("minecraft:ender_dragon")), List.of()),
				Optional.empty(), 0x1C0E2B, Tier.LEGENDARY, 3, "dragons_breath", "crystal_bane"));
		list.add(new SourceSummary(Identifier.fromNamespaceAndPath("absorbaholic", "iron_golem"),
				new SourceTargets(SourceKind.ENTITY, List.of(Identifier.parse("minecraft:iron_golem")), List.of()),
				Optional.empty(), 0xDAD6D0, Tier.EPIC, 3, "titan_strength", "iron_anchor"));
		list.add(block(Identifier.fromNamespaceAndPath("absorbaholic", "glass"), "minecraft:glass", null, "minecraft:glass", 0xC0E7F0,
				Tier.UNCOMMON, "glass_cannon", "fragile"));
		list.add(block(Identifier.fromNamespaceAndPath("absorbaholic", "diamond"), "minecraft:diamond_block", "minecraft:diamond_ores",
				"minecraft:diamond_ore", 0x4AEDD9, Tier.RARE, "diamond_skin", "brittle_brilliance"));
		list.add(block(Identifier.fromNamespaceAndPath("absorbaholic", "wool"), null, "minecraft:wool", "minecraft:white_wool", 0xE9ECEC,
				Tier.COMMON, "soft_landing", "soggy_wool"));
		list.add(block(Identifier.fromNamespaceAndPath("absorbaholic", "dirt"), null, "minecraft:dirt", "minecraft:dirt", 0x866043,
				Tier.COMMON, "down_to_earth", "weak_knees"));
		return list;
	}

	private static PlayerTraits ownTraits() {
		return new PlayerTraits(List.of(
				new TraitEntry(Identifier.fromNamespaceAndPath("absorbaholic", "ender_dragon"), 3, 2, true, false),
				new TraitEntry(LAVA, 1, 0, false, true),
				new TraitEntry(Identifier.fromNamespaceAndPath("absorbaholic", "iron_golem"), 2, 3, true, false),
				new TraitEntry(LEAVES, 2, 1, true, true),
				new TraitEntry(GONE, 1, 1, false, false),
				new TraitEntry(Identifier.fromNamespaceAndPath("absorbaholic", "glass"), 1, 1, false, false),
				new TraitEntry(Identifier.fromNamespaceAndPath("absorbaholic", "diamond"), 3, 3, false, false),
				new TraitEntry(Identifier.fromNamespaceAndPath("absorbaholic", "wool"), 1, 1, false, false),
				new TraitEntry(Identifier.fromNamespaceAndPath("absorbaholic", "dirt"), 2, 2, false, false),
				new TraitEntry(COW, 3, 3, false, false)));
	}

	private static void injectState(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			ClientState.setWorld(new WorldStatePayload(true, true, true, 20));
			ClientState.setSources(sources());
			ClientState.setDiscovered(true, List.of(LAVA, BEDROCK));
			ClientState.setOwnTraits(ownTraits());
			ClientState.setChannel(null, 0);
		});
	}

	// ---- HUD hint ----

	private static void hud(ClientGameTestContext ctx, TestSingleplayerContext sp, String lang) {
		BlockPos feet = ctx.computeOnClient(mc -> mc.player.blockPosition());
		BlockPos front = feet.east(2);
		ctx.getInput().holdKey(o -> o.keyShift);
		try {
			// undiscovered block source: name + ???
			sp.getServer().runCommand("setblock " + pos(front) + " minecraft:obsidian");
			List<String> undiscovered = hintFor(ctx, front, "undiscovered obsidian");
			expectLine(undiscovered, 1, Component.translatable("absorbaholic.hint.trait", Component.translatable("absorbaholic.hint.unknown")), "undiscovered trait");
			expectLine(undiscovered, 2, Component.translatable("absorbaholic.hint.weakness", Component.translatable("absorbaholic.hint.unknown")), "undiscovered weakness");
			ctx.takeScreenshot("ui_hint_undiscovered_" + lang);

			// discovered: real names
			ctx.runOnClient(mc -> ClientState.setDiscovered(false, List.of(OBSIDIAN)));
			ctx.waitTicks(2);
			List<String> discovered = hint(ctx, "discovered obsidian");
			expectLine(discovered, 1, Component.translatable("absorbaholic.hint.trait", Component.translatable("absorbaholic.trait.blast_proof")), "discovered trait");
			expectLine(discovered, 2, Component.translatable("absorbaholic.hint.weakness", Component.translatable("absorbaholic.weakness.dense")), "discovered weakness");
			if (discovered.size() != 3) throw new AssertionError("no status line expected for obsidian: " + discovered);

			// channel ring at ~40 %
			ctx.runOnClient(mc -> ClientState.setChannel(new ChannelStatePayload(ChannelStatePayload.Status.STARTED, 12, 30), 0));
			ctx.waitTicks(1);
			float progress = ctx.computeOnClient(mc -> AbsorbHud.channelProgress(0));
			if (progress < 0.35F || progress > 0.6F) throw new AssertionError("channel progress " + progress);
			ctx.takeScreenshot("ui_hint_channel_" + lang);
			ctx.runOnClient(mc -> ClientState.setChannel(new ChannelStatePayload(ChannelStatePayload.Status.CANCELLED, 12, 30), 0));
			ctx.waitTicks(1);
			if (ctx.computeOnClient(mc -> AbsorbHud.channelProgress(0)) >= 0) throw new AssertionError("ring still shown after CANCELLED");

			// full hand
			sp.getServer().runCommand("item replace entity @p weapon.mainhand with minecraft:stick");
			ctx.waitFor(mc -> !mc.player.getMainHandItem().isEmpty());
			ctx.waitTicks(2);
			expectLine(hint(ctx, "full hand"), 3, Component.translatable("absorbaholic.hint.empty_hand"), "full hand");
			sp.getServer().runCommand("item replace entity @p weapon.mainhand with minecraft:air");
			ctx.waitFor(mc -> mc.player.getMainHandItem().isEmpty());

			// full off hand (both hands must be empty, AbsorbRules.poseAllows)
			sp.getServer().runCommand("item replace entity @p weapon.offhand with minecraft:shield");
			ctx.waitFor(mc -> !mc.player.getOffhandItem().isEmpty());
			ctx.waitTicks(2);
			expectLine(hint(ctx, "full off hand"), 3, Component.translatable("absorbaholic.hint.empty_hand"), "full off hand");
			sp.getServer().runCommand("item replace entity @p weapon.offhand with minecraft:air");
			ctx.waitFor(mc -> mc.player.getOffhandItem().isEmpty());

			// not absorbable
			sp.getServer().runCommand("setblock " + pos(front) + " minecraft:gold_block");
			ctx.waitTicks(3);
			List<String> none = hint(ctx, "gold block");
			expectLine(none, 1, Component.translatable("absorbaholic.hint.not_absorbable"), "not absorbable");
			ctx.takeScreenshot("ui_hint_not_absorbable_" + lang);

			// protected bedrock (bottom 5 layers of the flat world)
			sp.getServer().runCommand("setblock " + pos(front) + " minecraft:bedrock");
			ctx.waitTicks(3);
			List<String> bedrock = hint(ctx, "bedrock");
			expectLine(bedrock, 3, Component.translatable("absorbaholic.hint.protected"), "protected");
			ctx.takeScreenshot("ui_hint_protected_" + lang);
			sp.getServer().runCommand("setblock " + pos(front) + " minecraft:air");

			// healthy mob → weaken it; weakened → no requirement
			sp.getServer().runCommand("summon minecraft:cow " + front.getX() + ".5 " + front.getY() + " " + front.getZ() + ".5 {NoAI:1b,Silent:1b,Rotation:[90f,0f]}");
			ctx.waitTicks(5);
			List<String> healthy = hintFor(ctx, front, "healthy cow");
			String percent = "25";
			expectLine(healthy, 3, Component.translatable("absorbaholic.hint.needs_health", percent), "healthy mob");
			expectLine(healthy, 1, Component.translatable("absorbaholic.hint.trait",
					TraitsScreen.traitText(ClientState.sources().byId(COW).orElseThrow(), 3)), "owned trait with level");
			ctx.takeScreenshot("ui_hint_mob_healthy_" + lang);
			sp.getServer().runOnServer(s -> s.overworld().getEntities(EntityTypes.COW, e -> true).forEach(c -> c.setHealth(2.0F)));
			ctx.waitFor(mc -> mc.level.getEntitiesOfClass(Cow.class, mc.player.getBoundingBox().inflate(5)).stream()
					.allMatch(c -> c.getHealth() <= 2.0F), 100);
			ctx.waitTicks(2);
			List<String> weak = hint(ctx, "weakened cow");
			if (weak.stream().anyMatch(l -> l.equals(Component.translatable("absorbaholic.hint.needs_health", percent).getString()))) {
				throw new AssertionError("weakened cow still needs health: " + weak);
			}

			// hints off / mode off → nothing
			ctx.runOnClient(mc -> ClientState.setWorld(new WorldStatePayload(true, false, true, 20)));
			ctx.waitTicks(2);
			if (ctx.computeOnClient(mc -> AbsorbHud.currentHint()) != null) throw new AssertionError("hint shown with hints off");
			ctx.runOnClient(mc -> ClientState.setWorld(new WorldStatePayload(false, true, true, 20)));
			ctx.waitTicks(2);
			if (ctx.computeOnClient(mc -> AbsorbHud.currentHint()) != null) throw new AssertionError("hint shown with mode off");
			ctx.runOnClient(mc -> ClientState.setWorld(new WorldStatePayload(true, true, true, 20)));
			// remove the cow and whatever it dropped (picked-up beef would fill the hand for the next pass)
			sp.getServer().runCommand("kill @e[type=minecraft:cow]");
			ctx.waitTicks(5);
			sp.getServer().runCommand("kill @e[type=minecraft:item]");
			sp.getServer().runCommand("clear @p");
			ctx.waitFor(mc -> mc.player.getInventory().isEmpty());
		} finally {
			ctx.getInput().releaseKey(o -> o.keyShift);
		}
		ctx.waitTicks(2);
		if (ctx.computeOnClient(mc -> AbsorbHud.currentHint()) != null) throw new AssertionError("hint shown without sneaking");
	}

	private static String pos(BlockPos p) {
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	private static List<String> hintFor(ClientGameTestContext ctx, BlockPos look, String what) {
		ctx.getInput().lookAt(look);
		ctx.waitTicks(3);
		return hint(ctx, what);
	}

	private static List<String> hint(ClientGameTestContext ctx, String what) {
		AbsorbHud.Hint hint = ctx.computeOnClient(mc -> AbsorbHud.currentHint());
		if (hint == null) throw new AssertionError("no hint for " + what);
		System.out.println("ABSORBAHOLIC_HINT " + what + ": " + hint.strings());
		return hint.strings();
	}

	private static void expectLine(List<String> lines, int index, Component expected, String what) {
		String want = expected.getString();
		if (lines.size() <= index || !lines.get(index).equals(want)) {
			throw new AssertionError(what + ": expected line " + index + " \"" + want + "\" in " + lines);
		}
	}

	// ---- aura ----

	/** Third person with a strong red-ish aura on the local player: screenshot for the visual review. */
	private static void aura(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			ClientState.setAura(new AuraPayload(mc.player.getId(), 0xD04040, 1.0F));
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		});
		ctx.waitTicks(40);
		ctx.takeScreenshot("ui_aura_third_person");
		ctx.runOnClient(mc -> {
			ClientState.setAura(new AuraPayload(mc.player.getId(), 0, 0.0F));
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		});
		if (ctx.computeOnClient(mc -> ClientState.aura(mc.player.getId())) != null) throw new AssertionError("aura not cleared");
	}

	// ---- traits screen ----

	private static void traitsScreen(ClientGameTestContext ctx, String lang) {
		// the default is K by name on the running version (a 26.3-built jar inlined SDL's KEY_K = 14 before, no key on 26.2)
		String defaultKey = ctx.computeOnClient(mc -> TraitsKeybind.key().getDefaultKey().getName());
		if (!"key.keyboard.k".equals(defaultKey)) throw new AssertionError("default traits key is not K: " + defaultKey);
		ctx.getInput().pressKey(o -> TraitsKeybind.key());
		ctx.waitForScreen(TraitsScreen.class);
		ctx.getInput().setCursorPos(0, 0); // opening a screen centres the cursor over a row: keep the overview tooltip-free
		ctx.waitTicks(1);
		int rows = ctx.computeOnClient(mc -> ((TraitsScreen) mc.gui.screen()).rowCount());
		if (rows != ownTraits().size()) throw new AssertionError("rows " + rows + ", expected " + ownTraits().size());
		String count = Component.translatable("absorbaholic.screen.traits.count", ownTraits().size(), 20).getString();
		stringWidget(ctx, count);
		stringWidget(ctx, Component.translatable("absorbaholic.screen.traits.title.own").getString());
		ctx.takeScreenshot("ui_traits_" + lang);

		// hover the first row → tooltip
		double[] cursor = ctx.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {mc.gui.screen().width / 2.0 * scale, 70 * scale};
		});
		ctx.getInput().setCursorPos(cursor[0], cursor[1]);
		ctx.waitTicks(2);
		ctx.takeScreenshot("ui_traits_tooltip_" + lang);
		ctx.getInput().setCursorPos(0, 0);

		// live update of own traits: one more entry appears
		ctx.runOnClient(mc -> ClientState.setOwnTraits(ownTraits().with(new TraitEntry(OBSIDIAN, 1, 1, false, false))));
		ctx.waitTicks(2);
		int updated = ctx.computeOnClient(mc -> ((TraitsScreen) mc.gui.screen()).rowCount());
		if (updated != ownTraits().size() + 1) throw new AssertionError("live update: rows " + updated);
		ctx.runOnClient(mc -> ClientState.setOwnTraits(ownTraits()));
		ctx.clickScreenButton("gui.done");
		ctx.waitForScreen(null);

		// another player's traits from the /absorbaholic traits payload
		ctx.runOnClient(mc -> TraitsScreen.open(mc, new TraitsPayload(UUID.randomUUID(), "Notch",
				new PlayerTraits(List.of(new TraitEntry(OBSIDIAN, 2, 1, false, true))), true)));
		ctx.waitForScreen(TraitsScreen.class);
		stringWidget(ctx, Component.translatable("absorbaholic.screen.traits.title", "Notch").getString());
		int other = ctx.computeOnClient(mc -> ((TraitsScreen) mc.gui.screen()).rowCount());
		if (other != 1) throw new AssertionError("other player's rows " + other);
		ctx.takeScreenshot("ui_traits_other_" + lang);
		ctx.clickScreenButton("gui.done");
		ctx.waitForScreen(null);

		// empty own list
		ctx.runOnClient(mc -> ClientState.setOwnTraits(PlayerTraits.EMPTY));
		ctx.getInput().pressKey(o -> TraitsKeybind.key());
		ctx.waitForScreen(TraitsScreen.class);
		ctx.takeScreenshot("ui_traits_empty_" + lang);
		ctx.clickScreenButton("gui.done");
		ctx.waitForScreen(null);
		ctx.runOnClient(mc -> ClientState.setOwnTraits(ownTraits()));
	}

	// ---- GUI scale 4 ----

	private static void guiScale4(ClientGameTestContext ctx) {
		ctx.getInput().resizeWindow(1920, 1080);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(4);
			mc.resizeGui();
		});
		ctx.waitTicks(3);
		ctx.getInput().holdKey(o -> o.keyShift);
		ctx.waitTicks(3);
		ctx.runOnClient(mc -> ClientState.setChannel(new ChannelStatePayload(ChannelStatePayload.Status.STARTED, 20, 30), 0));
		ctx.waitTicks(1);
		ctx.takeScreenshot("ui_scale4_hint_ru");
		ctx.getInput().releaseKey(o -> o.keyShift);
		ctx.getInput().pressKey(o -> TraitsKeybind.key());
		ctx.waitForScreen(TraitsScreen.class);
		ctx.takeScreenshot("ui_scale4_traits_ru");
		ctx.clickScreenButton("gui.done");
		ctx.waitForScreen(null);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(0);
			mc.resizeGui();
		});
		ctx.getInput().resizeWindow(854, 480);
		ctx.waitTicks(3);
	}

	// ---- helpers ----

	/** Same as picking a language in Options → Language, minus saving options.txt. */
	private static void setLanguage(ClientGameTestContext ctx, String code) {
		AtomicReference<CompletableFuture<Void>> reload = new AtomicReference<>();
		ctx.runOnClient(mc -> {
			mc.getLanguageManager().setSelected(code);
			mc.options.languageCode = code;
			reload.set(mc.reloadResourcePacks());
		});
		ctx.waitFor(mc -> reload.get().isDone() && mc.gui.overlay() == null, 20 * 120);
		String selected = ctx.computeOnClient(mc -> mc.getLanguageManager().getSelected());
		if (!code.equals(selected)) throw new AssertionError("language is " + selected + ", expected " + code);
	}

	/** The widget whose message (Button) or name (CycleButton) is the translation of {@code key}. */
	private static AbstractWidget widget(ClientGameTestContext ctx, String key) {
		return ctx.computeOnClient(mc -> {
			String label = Component.translatable(key).getString();
			return mc.gui.screen().children().stream()
					.filter(c -> c instanceof AbstractWidget)
					.map(c -> (AbstractWidget) c)
					.filter(w -> w.getMessage().getString().startsWith(label))
					.findFirst()
					.orElseThrow(() -> new AssertionError("no widget \"" + label + "\" on " + mc.gui.screen()));
		});
	}

	/** Fails unless the current screen shows a text widget with exactly {@code text}. */
	private static void stringWidget(ClientGameTestContext ctx, String text) {
		boolean found = ctx.computeOnClient(mc -> mc.gui.screen().children().stream()
				.anyMatch(r -> r instanceof StringWidget w && w.getMessage().getString().equals(text)));
		if (!found) throw new AssertionError("no text \"" + text + "\" on the screen");
	}
}
