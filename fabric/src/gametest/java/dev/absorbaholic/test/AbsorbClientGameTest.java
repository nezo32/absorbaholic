package dev.absorbaholic.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import dev.absorbaholic.client.CreateWorldModeHolder;
import dev.absorbaholic.world.AbsorbWorldSettings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Client gametest (not part of {@code build}; run {@code ./gradlew runClientGameTest} under Xvfb).
 * <p>WP-CW section, the Create World "Absorbaholic Mode" button:
 * <ol>
 * <li>Layout: the button is the grid row right below Difficulty and right above Allow Cheats (widget order,
 *     same column, 28 px row pitch), starts ON, toggles OFF/ON; screenshot of the Game tab.</li>
 * <li>Vanilla behaviour is intact: Difficulty still cycles Peaceful/Easy/Normal/Hard through uiState; Hardcore
 *     still locks Difficulty to Hard and disables Allow Cheats, while our button stays usable and keeps its value.</li>
 * <li>World 1 (default ON, Hard, cheats on): settings enabled in memory and in settings.dat, difficulty Hard.</li>
 * <li>Cancel after switching OFF, then world 2 untouched: ON (nothing leaked from the cancelled screen).</li>
 * <li>World 3 (Hardcore, switched OFF): settings disabled, hardcore, difficulty Hard.</li>
 * <li>Re-Create world 3: the button starts at the default ON (not copied); the new world is ON.
 *     Re-open world 1 and world 3: settings.dat is read back (ON / OFF), no pending value applied.</li>
 * <li>Labels: both en_us and ru_ru "name: ON/OFF" fit the 210 px button; ru_ru screenshot.</li>
 * </ol>
 * WP-CLIENT / WP-UI: append your sections after the WP-CW section, in separate private methods.
 */
public class AbsorbClientGameTest implements FabricClientGameTest {
	private static final String TOGGLE = "absorbaholic.createWorld.toggle";
	private static final String DIFFICULTY = "options.difficulty";
	private static final String ALLOW_COMMANDS = "selectWorld.allowCommands";
	/** CycleButton width minus the 2 px text margin on each side (AbstractButton.TEXT_MARGIN). */
	private static final int MAX_LABEL_WIDTH = 210 - 2 * 2;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		createWorldButton(ctx);
	}

	// ---- WP-CW: Create World button ----

	private static void createWorldButton(ClientGameTestContext ctx) {
		requireTranslation(ctx, TOGGLE);
		requireTranslation(ctx, TOGGLE + ".tooltip");

		// 1. layout, default, toggle
		openCreateWorld(ctx);
		assertLayout(ctx);
		if (!uiMode(ctx) || !toggleValue(ctx)) throw new AssertionError("fresh Create World screen does not start ON");
		Path shotEn = ctx.takeScreenshot("absorbaholic_create_world_game_tab_en");
		boolean[] seen = new boolean[4];
		for (int i = 0; i < 4; i++) {
			ctx.clickScreenButton(TOGGLE);
			seen[i] = uiMode(ctx);
			if (seen[i] != toggleValue(ctx)) throw new AssertionError("button value and screen value differ after click " + (i + 1));
		}
		if (seen[0] || !seen[1] || seen[2] || !seen[3]) {
			throw new AssertionError("toggle sequence wrong: " + seen[0] + "/" + seen[1] + "/" + seen[2] + "/" + seen[3]);
		}
		assertLabelFits(ctx, "en_us");

		// 2. Difficulty still cycles through every value; our button is untouched by it
		Difficulty start = uiDifficulty(ctx);
		for (int i = 1; i <= Difficulty.values().length; i++) {
			ctx.clickScreenButton(DIFFICULTY);
			Difficulty expected = Difficulty.values()[(start.ordinal() + i) % Difficulty.values().length];
			Difficulty actual = uiDifficulty(ctx);
			if (actual != expected) throw new AssertionError("Difficulty click " + i + ": " + actual + ", expected " + expected);
			if (buttonValue(ctx, DIFFICULTY) != expected) throw new AssertionError("Difficulty button shows " + buttonValue(ctx, DIFFICULTY));
			if (!uiMode(ctx)) throw new AssertionError("Difficulty click changed Absorbaholic Mode");
		}
		for (Difficulty d : List.of(Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD)) {
			ctx.runOnClient(mc -> uiState(mc).setDifficulty(d));
			if (buttonValue(ctx, DIFFICULTY) != d || !widget(ctx, DIFFICULTY).active) {
				throw new AssertionError("Difficulty " + d + " not shown/active in survival");
			}
		}
		// Hardcore: vanilla locks Difficulty (Hard) and Allow Cheats; our button stays active and keeps its value
		ctx.runOnClient(mc -> uiState(mc).setDifficulty(Difficulty.EASY));
		ctx.runOnClient(mc -> uiState(mc).setGameMode(WorldCreationUiState.SelectedGameMode.HARDCORE));
		if (widget(ctx, DIFFICULTY).active) throw new AssertionError("Hardcore no longer locks Difficulty");
		if (buttonValue(ctx, DIFFICULTY) != Difficulty.HARD) throw new AssertionError("Hardcore Difficulty is " + buttonValue(ctx, DIFFICULTY));
		if (widget(ctx, ALLOW_COMMANDS).active) throw new AssertionError("Hardcore no longer locks Allow Cheats");
		if (!widget(ctx, TOGGLE).active || !uiMode(ctx)) throw new AssertionError("Hardcore disabled or changed Absorbaholic Mode");
		assertLayout(ctx);
		ctx.runOnClient(mc -> uiState(mc).setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL));
		if (!widget(ctx, DIFFICULTY).active || uiDifficulty(ctx) != Difficulty.EASY) {
			throw new AssertionError("leaving Hardcore did not restore Difficulty: " + uiDifficulty(ctx));
		}

		// 3. world 1: default ON, Hard, cheats on
		ctx.runOnClient(mc -> {
			uiState(mc).setDifficulty(Difficulty.HARD);
			uiState(mc).setAllowCommands(true);
		});
		Path world1 = createWorld(ctx);
		assertMode(ctx, true, "world 1 (default ON)");
		assertSaved(ctx, world1, true);
		assertDifficulty(ctx, Difficulty.HARD, false, "world 1");
		leaveWorld(ctx);

		// 4. Cancel after switching OFF must not leak into the next world
		ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
		ctx.waitForScreen(CreateWorldScreen.class);
		ctx.clickScreenButton(TOGGLE);
		if (uiMode(ctx)) throw new AssertionError("toggle before Cancel did not turn OFF");
		ctx.clickScreenButton("gui.cancel");
		ctx.waitForScreen(TitleScreen.class);
		openCreateWorld(ctx);
		if (!uiMode(ctx) || !toggleValue(ctx)) throw new AssertionError("OFF leaked from the cancelled screen into a new one");
		Path world2 = createWorld(ctx);
		assertMode(ctx, true, "world 2 (after a cancelled OFF screen)");
		assertSaved(ctx, world2, true);
		leaveWorld(ctx);

		// 5. world 3: Hardcore, switched OFF
		openCreateWorld(ctx);
		ctx.runOnClient(mc -> uiState(mc).setGameMode(WorldCreationUiState.SelectedGameMode.HARDCORE));
		ctx.clickScreenButton(TOGGLE);
		if (uiMode(ctx)) throw new AssertionError("toggle in Hardcore did not turn OFF");
		Path world3 = createWorld(ctx);
		assertMode(ctx, false, "world 3 (Hardcore, OFF)");
		assertSaved(ctx, world3, false);
		assertDifficulty(ctx, Difficulty.HARD, true, "world 3");
		leaveWorld(ctx);
		if (world1.equals(world2) || world2.equals(world3) || world1.equals(world3)) throw new AssertionError("world folder reused");

		// 6. Re-Create world 3 starts at the default ON; re-open worlds read settings.dat back
		openWorldList(ctx);
		ctx.runOnClient(mc -> worldEntry(mc, world3).recreateWorld());
		ctx.waitForScreen(CreateWorldScreen.class);
		if (!uiMode(ctx) || !toggleValue(ctx)) throw new AssertionError("Re-Create screen does not start ON (copied OFF?)");
		Path world4 = createWorld(ctx);
		assertMode(ctx, true, "re-created world 3");
		assertSaved(ctx, world4, true);
		leaveWorld(ctx);
		joinWorld(ctx, world1);
		assertMode(ctx, true, "world 1 re-opened");
		leaveWorld(ctx);
		joinWorld(ctx, world3);
		assertMode(ctx, false, "world 3 re-opened");
		leaveWorld(ctx);

		// 7. ru_ru label fits and screenshot
		setLanguage(ctx, "ru_ru");
		try {
			ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
			ctx.waitForScreen(CreateWorldScreen.class);
			assertLayout(ctx);
			String ruLabel = widget(ctx, TOGGLE).getMessage().getString();
			if (!ruLabel.startsWith("Режим Absorbaholic")) throw new AssertionError("ru_ru toggle reads \"" + ruLabel + "\"");
			Path shotRu = ctx.takeScreenshot("absorbaholic_create_world_game_tab_ru");
			assertLabelFits(ctx, "ru_ru");
			ctx.clickScreenButton("gui.cancel");
			ctx.waitForScreen(TitleScreen.class);
			System.out.println("ABSORBAHOLIC_CW screenshots: " + shotEn + " " + shotRu + " ru label=\"" + ruLabel + "\"");
		} finally {
			setLanguage(ctx, "en_us");
		}
		ctx.setScreen(TitleScreen::new);
		System.out.println("ABSORBAHOLIC_CW_TEST_OK worlds=" + world1.getFileName() + "," + world2.getFileName() + ","
				+ world3.getFileName() + "," + world4.getFileName());
	}

	/** Difficulty, our toggle and Allow Cheats are consecutive children, one 28 px row apart, in the same column. */
	private static void assertLayout(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			List<AbstractWidget> widgets = mc.gui.screen().children().stream()
					.filter(c -> c instanceof AbstractWidget).map(c -> (AbstractWidget) c).toList();
			int d = indexOf(widgets, DIFFICULTY), t = indexOf(widgets, TOGGLE), c = indexOf(widgets, ALLOW_COMMANDS);
			if (t != d + 1 || c != t + 1) throw new AssertionError("widget order: difficulty=" + d + " toggle=" + t + " allowCommands=" + c);
			AbstractWidget dw = widgets.get(d), tw = widgets.get(t), cw = widgets.get(c);
			if (tw.getY() != dw.getY() + 28 || cw.getY() != tw.getY() + 28) {
				throw new AssertionError("rows: difficulty y=" + dw.getY() + " toggle y=" + tw.getY() + " allowCommands y=" + cw.getY());
			}
			if (tw.getX() != dw.getX() || tw.getX() != cw.getX() || tw.getWidth() != 210 || tw.getHeight() != 20) {
				throw new AssertionError("toggle at x=" + tw.getX() + " w=" + tw.getWidth() + " h=" + tw.getHeight()
						+ ", difficulty x=" + dw.getX() + ", allowCommands x=" + cw.getX());
			}
			if (!tw.visible) throw new AssertionError("toggle not visible on the Game tab");
		});
	}

	/** "Absorbaholic Mode: ON" and ": OFF" in the current language fit the button without scrolling. */
	private static void assertLabelFits(ClientGameTestContext ctx, String language) {
		for (int i = 0; i < 2; i++) {
			int width = ctx.computeOnClient(mc -> mc.font.width(widget(mc, TOGGLE).getMessage()));
			String text = widget(ctx, TOGGLE).getMessage().getString();
			System.out.println("ABSORBAHOLIC_CW label " + language + " \"" + text + "\" = " + width + " px");
			if (width > MAX_LABEL_WIDTH) throw new AssertionError(language + " label \"" + text + "\" is " + width + " px > " + MAX_LABEL_WIDTH);
			ctx.clickScreenButton(TOGGLE);
		}
	}

	private static void requireTranslation(ClientGameTestContext ctx, String key) {
		if (!ctx.computeOnClient(mc -> Language.getInstance().has(key))) {
			throw new AssertionError("missing en_us translation " + key + " (merge scratchpad/lang/cw-*.json)");
		}
	}

	private static int indexOf(List<AbstractWidget> widgets, String key) {
		String label = Component.translatable(key).getString();
		for (int i = 0; i < widgets.size(); i++) {
			if (widgets.get(i).getMessage().getString().startsWith(label)) return i;
		}
		throw new AssertionError("no widget " + label);
	}

	private static AbstractWidget widget(Minecraft mc, String key) {
		List<AbstractWidget> widgets = mc.gui.screen().children().stream()
				.filter(c -> c instanceof AbstractWidget).map(c -> (AbstractWidget) c).toList();
		return widgets.get(indexOf(widgets, key));
	}

	private static AbstractWidget widget(ClientGameTestContext ctx, String key) {
		return ctx.computeOnClient(mc -> widget(mc, key));
	}

	private static Object buttonValue(ClientGameTestContext ctx, String key) {
		return ctx.computeOnClient(mc -> ((CycleButton<?>) widget(mc, key)).getValue());
	}

	private static boolean toggleValue(ClientGameTestContext ctx) {
		return (Boolean) buttonValue(ctx, TOGGLE);
	}

	private static boolean uiMode(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> ((CreateWorldModeHolder) mc.gui.screen()).absorbaholic$isModeEnabled());
	}

	private static WorldCreationUiState uiState(Minecraft mc) {
		return ((CreateWorldScreen) mc.gui.screen()).getUiState();
	}

	private static Difficulty uiDifficulty(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> uiState(mc).getDifficulty());
	}

	private static void openCreateWorld(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> {}));
		ctx.waitForScreen(CreateWorldScreen.class);
	}

	/** Clicks Create, waits until the player is in the world and returns the world folder. */
	private static Path createWorld(ClientGameTestContext ctx) {
		ctx.clickScreenButton("selectWorld.create");
		return waitInWorld(ctx);
	}

	private static Path waitInWorld(ClientGameTestContext ctx) {
		ctx.waitFor(mc -> mc.getSingleplayerServer() != null && mc.player != null, 20 * 60);
		return ctx.computeOnClient(mc -> mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
	}

	// computeOnClient, not server.submit(..).join(): the client-gametest framework holds the server thread while the test runs
	private static void assertMode(ClientGameTestContext ctx, boolean expected, String what) {
		boolean actual = ctx.computeOnClient(mc -> AbsorbWorldSettings.isEnabled(mc.getSingleplayerServer()));
		if (actual != expected) throw new AssertionError(what + ": mode " + actual + ", expected " + expected);
	}

	private static void assertDifficulty(ClientGameTestContext ctx, Difficulty expected, boolean hardcore, String what) {
		Difficulty actual = ctx.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty());
		boolean actualHardcore = ctx.computeOnClient(mc -> mc.getSingleplayerServer().isHardcore());
		if (actual != expected || actualHardcore != hardcore) {
			throw new AssertionError(what + ": difficulty " + actual + " hardcore " + actualHardcore + ", expected " + expected + " / " + hardcore);
		}
	}

	/** settings.dat is written right after creation (WorldSettingsBootstrap saves at once); wait for the async write. */
	private static void assertSaved(ClientGameTestContext ctx, Path world, boolean expected) {
		Path file = world.resolve("data/absorbaholic/settings.dat");
		ctx.waitFor(mc -> Files.isRegularFile(file), 20 * 10);
		try {
			CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			// Strict: ON must be written explicitly; OFF may be omitted (optionalFieldOf does not write its default), so
			// the file is also decoded with the real codec, which is how the game reads it back.
			CompoundTag data = root.getCompound("data").orElseThrow(() -> new AssertionError(file + ": no data in " + root));
			if (expected && data.getBoolean("enabled").isEmpty()) throw new AssertionError(file + ": no 'enabled' key in " + root);
			boolean saved = AbsorbWorldSettings.CODEC.parse(NbtOps.INSTANCE, data).getOrThrow(AssertionError::new).enabled();
			if (saved != expected) throw new AssertionError(file + ": enabled " + saved + ", expected " + expected + " (" + root + ")");
		} catch (IOException e) {
			throw new AssertionError("cannot read " + file, e);
		}
	}

	/** Leaves the world; otherwise the client-gametest framework fails ("finished while a server is still running"). */
	private static void leaveWorld(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.level.disconnect(Component.translatable("menu.savingLevel"));
			mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), false);
		});
		ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 20 * 60);
		ctx.setScreen(TitleScreen::new);
		ctx.waitTicks(20);
	}

	private static void openWorldList(ClientGameTestContext ctx) {
		ctx.setScreen(() -> new SelectWorldScreen(new TitleScreen()));
		ctx.waitFor(mc -> worldList(mc).map(l -> l.children().stream()
				.anyMatch(e -> e instanceof WorldSelectionList.WorldListEntry)).orElse(false), 20 * 30);
	}

	private static void joinWorld(ClientGameTestContext ctx, Path world) {
		openWorldList(ctx);
		ctx.runOnClient(mc -> worldEntry(mc, world).joinWorld());
		Path joined = waitInWorld(ctx);
		if (!joined.equals(world)) throw new AssertionError("joined " + joined + " instead of " + world);
	}

	private static Optional<WorldSelectionList> worldList(Minecraft mc) {
		if (!(mc.gui.screen() instanceof SelectWorldScreen screen)) return Optional.empty();
		return screen.children().stream().filter(c -> c instanceof WorldSelectionList).map(c -> (WorldSelectionList) c).findFirst();
	}

	private static WorldSelectionList.WorldListEntry worldEntry(Minecraft mc, Path world) {
		String id = world.getFileName().toString();
		return worldList(mc).orElseThrow().children().stream()
				.filter(e -> e instanceof WorldSelectionList.WorldListEntry)
				.map(e -> (WorldSelectionList.WorldListEntry) e)
				.filter(e -> e.getLevelSummary().getLevelId().equals(id))
				.findFirst().orElseThrow(() -> new AssertionError("world " + id + " not in the world list"));
	}

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
}
