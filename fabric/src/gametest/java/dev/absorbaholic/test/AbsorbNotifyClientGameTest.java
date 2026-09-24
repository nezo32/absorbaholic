package dev.absorbaholic.test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import dev.absorbaholic.absorb.AbsorbTarget;
import dev.absorbaholic.client.ClientState;
import dev.absorbaholic.client.input.AbsorbInput;
import dev.absorbaholic.client.notify.NotifyClient;
import dev.absorbaholic.client.notify.NotifyConfig;
import dev.absorbaholic.core.MutationRoll;
import dev.absorbaholic.core.NotifySettings;
import dev.absorbaholic.net.AbsorbCancelPayload;
import dev.absorbaholic.net.AbsorbStartPayload;
import dev.absorbaholic.net.AbsorbedPayload;
import dev.absorbaholic.net.ChannelStatePayload;
import dev.absorbaholic.net.WorldStatePayload;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.registry.SourceSummary;
import dev.absorbaholic.world.AbsorbWorldSettings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * WP-CLIENT client gametest (runClientGameTest under Xvfb).
 * <ol>
 * <li>Title measuring: the full "Absorbed: name" title exactly when {@code width * 4 <= guiWidth - 8}, else the short
 *     title with the name (and the outcome line) in the subtitle.</li>
 * <li>{@code /absorbaholic-notify} toggles, flips and persists the settings.</li>
 * <li>NotifyClient honours message OFF / sound OFF, and the {@code absorbed} receiver shows a server-sent payload.</li>
 * <li>The use-key intercept in a singleplayer world, on a note block placed by the server (vanilla use cycles its
 *     note, which makes "vanilla use happened" observable): vanilla use is untouched without sneaking, with the mode OFF,
 *     with a full hand, on a non-absorbable block and when the server lacks the mod's channel; sneak + empty hand +
 *     held use sends a heartbeat every tick and one cancel on release. With the server absorb handler installed the
 *     block is consumed after the 30-tick channel; without it the test installs a recording receiver (so the channel
 *     is advertised) and asserts the start payloads that reached the server.</li>
 * </ol>
 */
public class AbsorbNotifyClientGameTest implements FabricClientGameTest {
	private static final Identifier SOURCE_ID = Identifier.fromNamespaceAndPath("absorbaholic_test", "notify_client_note");
	private static final String SENTINEL = "sentinel";
	private static final int VANILLA_HOLD_TICKS = 6;
	/** Channel (30 ticks) plus latency. */
	private static final int ABSORB_HOLD_TICKS = 40;

	/** What the recording receivers saw (only used when the server absorb handler is not installed). */
	private static final Queue<AbsorbTarget> starts = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger cancels = new AtomicInteger();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		NotifySettings original = NotifyConfig.get();
		try {
			titleMeasurement(ctx);
			inWorld(ctx);
		} finally {
			NotifyConfig.set(original);
		}
		System.out.println("ABSORBAHOLIC_NOTIFY_CLIENT_TEST_OK");
	}

	// ---- title measuring ------------------------------------------------------------------------------------------

	private static void titleMeasurement(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			Component name = Component.literal("Ice");
			Component full = Component.translatable("absorbaholic.absorbed.title", name);
			int exact = mc.font.width(full) * NotifyClient.TITLE_SCALE + NotifyClient.TITLE_MARGIN;

			NotifyClient.TitleLines fits = NotifyClient.composeTitle(name, MutationRoll.Outcome.NORMAL, mc.font, exact);
			check(fits.title().equals(full), "title must be the full one at exactly its width: " + fits);
			check(fits.subtitle() == null, "a normal absorption with the full title has no subtitle: " + fits);

			NotifyClient.TitleLines tooNarrow = NotifyClient.composeTitle(name, MutationRoll.Outcome.NORMAL, mc.font, exact - 1);
			check(tooNarrow.title().equals(Component.translatable("absorbaholic.absorbed.title.short")),
					"one pixel too narrow must use the short title: " + tooNarrow);
			check(tooNarrow.subtitle() != null && tooNarrow.subtitle().getString().equals("Ice"),
					"the short title moves the name to the subtitle: " + tooNarrow);

			Component pure = Component.translatable("absorbaholic.absorbed.subtitle.pure");
			NotifyClient.TitleLines pureFits = NotifyClient.composeTitle(name, MutationRoll.Outcome.PURE, mc.font, exact);
			check(pureFits.subtitle() != null && pureFits.subtitle().equals(pure), "pure + full title: the pure line only: " + pureFits);
			NotifyClient.TitleLines pureShort = NotifyClient.composeTitle(name, MutationRoll.Outcome.MUTATE_WEAKNESS, mc.font, exact - 1);
			String expected = "Ice · " + Component.translatable("absorbaholic.absorbed.subtitle.mutate_weakness").getString();
			check(pureShort.subtitle() != null && pureShort.subtitle().getString().equals(expected),
					"special + short title: name · outcome line, got " + pureShort);

			// the real screen: a very long name never fits at title scale
			int gui = mc.getWindow().getGuiScaledWidth();
			Component longName = Component.literal("Extraordinarily Long Source Name Of Many Words");
			NotifyClient.TitleLines real = NotifyClient.composeTitle(longName, MutationRoll.Outcome.NORMAL, mc.font, gui);
			check(!NotifyClient.fitsTitle(mc.font, Component.translatable("absorbaholic.absorbed.title", longName), gui),
					"test premise: the long name overflows at " + gui);
			check(real.subtitle() != null && real.subtitle().getString().equals(longName.getString()), "long name → subtitle: " + real);
			NotifyClient.TitleLines shortReal = NotifyClient.composeTitle(name, MutationRoll.Outcome.NORMAL, mc.font, gui);
			boolean fitsReal = NotifyClient.fitsTitle(mc.font, full, gui);
			check(shortReal.title().equals(fitsReal ? full : Component.translatable("absorbaholic.absorbed.title.short")),
					"title choice must follow the measurement at " + gui + ": " + shortReal);
		});
	}

	// ---- world ------------------------------------------------------------------------------------------------------

	private static void inWorld(ClientGameTestContext ctx) {
		boolean ownReceivers = !ServerPlayNetworking.getGlobalReceivers().contains(AbsorbStartPayload.TYPE.id());
		if (ownReceivers) {
			// No server absorb handler in this build yet: advertise the channel with a recording receiver.
			ServerPlayNetworking.registerGlobalReceiver(AbsorbStartPayload.TYPE, (p, c) -> starts.add(p.target()));
			ServerPlayNetworking.registerGlobalReceiver(AbsorbCancelPayload.TYPE, (p, c) -> cancels.incrementAndGet());
		}
		List<SourceDefinition> savedSources = SourceRegistry.all();
		try (TestSingleplayerContext sp = ctx.worldBuilder()
				.adjustSettings(s -> {
					s.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
					s.setAllowCommands(true);
				})
				.create()) {
			ctx.waitFor(mc -> mc.player != null, 20 * 60);
			sp.getConnection().waitForChunksRender();
			waitForClientChannel(ctx, true);

			notifyCommand(ctx);
			notifySettings(ctx);
			absorbedReceiver(ctx, sp);
			useIntercept(ctx, sp, ownReceivers);
		} finally {
			SourceRegistry.set(savedSources);
			if (ownReceivers) {
				ServerPlayNetworking.unregisterGlobalReceiver(AbsorbStartPayload.TYPE.id());
				ServerPlayNetworking.unregisterGlobalReceiver(AbsorbCancelPayload.TYPE.id());
			}
		}
	}

	private static void notifyCommand(ClientGameTestContext ctx) {
		NotifyConfig.set(NotifySettings.DEFAULT);
		command(ctx, "absorbaholic-notify sound off");
		expectSettings(new NotifySettings(false, true), "sound off");
		command(ctx, "absorbaholic-notify message off");
		expectSettings(new NotifySettings(false, false), "message off");
		command(ctx, "absorbaholic-notify sound");
		expectSettings(new NotifySettings(true, false), "sound flipped");
		command(ctx, "absorbaholic-notify message on");
		expectSettings(NotifySettings.DEFAULT, "message on");
		command(ctx, "absorbaholic-notify status");
		expectSettings(NotifySettings.DEFAULT, "status changes nothing");
	}

	private static void command(ClientGameTestContext ctx, String command) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
		ctx.waitTick();
	}

	private static void expectSettings(NotifySettings expected, String what) {
		check(NotifyConfig.get().equals(expected), what + ": memory " + NotifyConfig.get() + ", expected " + expected);
		NotifySettings onDisk = NotifySettings.load(NotifyConfig.path());
		check(onDisk.equals(expected), what + ": file " + onDisk + ", expected " + expected);
	}

	private static AbsorbedPayload payload(MutationRoll.Outcome outcome) {
		return new AbsorbedPayload(Component.literal("Ice"), Component.literal("Slippery II · Brittle I"), outcome,
				Optional.of(Component.literal("evicted")));
	}

	private static void notifySettings(ClientGameTestContext ctx) {
		// message OFF, sound ON: nothing on screen, the sound plays
		NotifyConfig.set(new NotifySettings(true, false));
		ctx.runOnClient(mc -> {
			resetHud(mc);
			NotifyClient.Shown shown = NotifyClient.handle(payload(MutationRoll.Outcome.PURE), mc);
			check(shown.title() == null && shown.actionbar() == null && shown.notice() == null, "message OFF but shown: " + shown);
			check(shown.sound() == NotifyClient.soundOf(MutationRoll.Outcome.PURE).sound(), "sound ON but played " + shown.sound());
			check(SENTINEL.equals(text(hudField(mc, "overlayMessageString"))), "message OFF but the actionbar changed");
			check(hudField(mc, "title") == null, "message OFF but a title is shown");
		});

		// message ON, sound OFF: title, subtitle, actionbar and notice, no sound
		NotifyConfig.set(new NotifySettings(false, true));
		ctx.runOnClient(mc -> {
			resetHud(mc);
			NotifyClient.Shown shown = NotifyClient.handle(payload(MutationRoll.Outcome.MUTATE_TRAIT), mc);
			check(shown.sound() == null, "sound OFF but played " + shown.sound());
			check(shown.title() != null && shown.notice() != null, "message ON but nothing shown: " + shown);
			check("Slippery II · Brittle I".equals(text(hudField(mc, "overlayMessageString"))), "actionbar not shown");
			check(shown.title().title().equals(hudField(mc, "title")), "HUD title differs from the composed one");
			check(shown.title().subtitle() != null && shown.title().subtitle().equals(hudField(mc, "subtitle")),
					"a mutation must show its subtitle line");
		});

		// both ON: the normal outcome's sound
		NotifyConfig.set(NotifySettings.DEFAULT);
		ctx.runOnClient(mc -> {
			resetHud(mc);
			NotifyClient.Shown shown = NotifyClient.handle(payload(MutationRoll.Outcome.NORMAL), mc);
			check(shown.sound() == NotifyClient.soundOf(MutationRoll.Outcome.NORMAL).sound(), "normal sound expected: " + shown);
			check(shown.title() != null, "title expected");
		});
	}

	/** The receiver: a payload sent by the server reaches NotifyClient. */
	private static void absorbedReceiver(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		NotifyConfig.set(NotifySettings.DEFAULT);
		ctx.runOnClient(AbsorbNotifyClientGameTest::resetHud);
		sp.getServer().runOnServer(s -> ServerPlayNetworking.send(player(s), payload(MutationRoll.Outcome.NORMAL)));
		ctx.waitFor(mc -> !SENTINEL.equals(text(hudField(mc, "overlayMessageString"))), 20 * 5);
		ctx.runOnClient(mc -> check(hudField(mc, "title") != null, "the absorbed payload showed no title"));
	}

	// ---- use intercept ----------------------------------------------------------------------------------------------

	private static void useIntercept(ClientGameTestContext ctx, TestSingleplayerContext sp, boolean ownReceivers) {
		TestInput in = ctx.getInput();
		BlockPos pos = sp.getServer().computeOnServer(s -> {
			ServerPlayer p = player(s);
			BlockPos target = p.blockPosition().relative(Direction.EAST, 2).above();
			p.level().setBlockAndUpdate(target, Blocks.NOTE_BLOCK.defaultBlockState());
			p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			return target;
		});
		SourceDefinition note = source(Blocks.NOTE_BLOCK);
		SourceDefinition other = source(Blocks.EMERALD_BLOCK);
		setMode(ctx, sp, true);
		setSources(ctx, sp, note);
		ctx.waitFor(mc -> mc.level != null && mc.level.getBlockState(pos).is(Blocks.NOTE_BLOCK), 20 * 5);
		in.lookAt(pos);
		ctx.waitTicks(2);

		// 1. not sneaking: vanilla use cycles the note
		expectVanillaUse(ctx, sp, pos, "not sneaking");

		in.holdKey(o -> o.keyShift);
		ctx.waitTicks(3);
		in.lookAt(pos); // the eyes moved down
		ctx.waitTicks(2);

		// 2. mode OFF
		setMode(ctx, sp, false);
		expectVanillaUse(ctx, sp, pos, "mode OFF");
		setMode(ctx, sp, true);

		// 3. a full hand: vanilla places the held block against the note block
		sp.getServer().runOnServer(s -> player(s).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 4)));
		ctx.waitFor(mc -> mc.player.getMainHandItem().is(Items.DIRT), 20 * 5);
		expectNoGesture(ctx, "hand full");
		long beforeHand = heartbeats(ctx);
		in.holdKeyFor(o -> o.keyUse, VANILLA_HOLD_TICKS);
		ctx.waitTicks(5);
		boolean placed = sp.getServer().computeOnServer(s -> {
			boolean found = false;
			for (Direction d : Direction.values()) {
				BlockPos n = pos.relative(d);
				if (player(s).level().getBlockState(n).is(Blocks.DIRT)) {
					found = true;
					player(s).level().setBlockAndUpdate(n, Blocks.AIR.defaultBlockState());
				}
			}
			player(s).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			return found;
		});
		check(placed, "hand full: vanilla use must place the held dirt");
		check(heartbeats(ctx) == beforeHand, "hand full: no absorb heartbeat may be sent");
		ctx.waitFor(mc -> mc.player.getMainHandItem().isEmpty(), 20 * 5);

		// 4. the block is not absorbable
		setSources(ctx, sp, other);
		expectVanillaUse(ctx, sp, pos, "not absorbable");
		setSources(ctx, sp, note);

		// 5. the server lacks the mod's channel (and sources, so no server-side use guard can interfere)
		sp.getServer().runOnServer(s -> SourceRegistry.set(List.of()));
		serverLacksMod(ctx, sp, pos);
		setSources(ctx, sp, note);

		// 6. the gesture: sneak + empty hand + hold use
		ctx.runOnClient(mc -> check(AbsorbInput.shouldIntercept(mc), "all preconditions hold but no intercept"));
		absorb(ctx, sp, pos, ownReceivers);
		in.releaseKey(o -> o.keyShift);
	}

	private static void absorb(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos pos, boolean ownReceivers) {
		TestInput in = ctx.getInput();
		int noteBefore = note(sp, pos);
		long heartbeatsBefore = heartbeats(ctx);
		long cancelsBefore = ctx.computeOnClient(mc -> AbsorbInput.cancelsSent());
		starts.clear();
		cancels.set(0);
		boolean[] channelStarted = {false};

		in.holdKey(o -> o.keyUse);
		for (int i = 0; i < ABSORB_HOLD_TICKS; i++) {
			ctx.waitTick();
			ctx.runOnClient(mc -> {
				ChannelStatePayload ch = ClientState.channel();
				if (ch != null && ch.status() != ChannelStatePayload.Status.CANCELLED) channelStarted[0] = true;
			});
			if (i == 5) {
				AbsorbTarget active = ctx.computeOnClient(mc -> AbsorbInput.activeTarget());
				check(AbsorbTarget.block(pos).equals(active), "holding: active target " + active + ", expected the note block");
			}
		}
		in.releaseKey(o -> o.keyUse);
		ctx.waitTicks(5);

		long sent = heartbeats(ctx) - heartbeatsBefore;
		long cancelled = ctx.computeOnClient(mc -> AbsorbInput.cancelsSent()) - cancelsBefore;
		check(sent >= 25, "a heartbeat every held tick expected, got " + sent + " in " + ABSORB_HOLD_TICKS + " ticks");
		check(cancelled >= 1, "release must send a cancel");
		ctx.runOnClient(mc -> check(AbsorbInput.activeTarget() == null, "the gesture must end on release"));

		if (ownReceivers) {
			// server side not installed yet: the start payloads (heartbeat) and the cancel reached the server
			ctx.waitFor(mc -> cancels.get() >= 1, 20 * 5);
			check(starts.size() >= 25, "the server received only " + starts.size() + " start payloads");
			check(starts.stream().allMatch(AbsorbTarget.block(pos)::equals), "a start payload carried another target: " + starts);
			check(note(sp, pos) == noteBefore, "the intercept must suppress vanilla use (the note changed)");
		} else {
			boolean gone = sp.getServer().computeOnServer(s -> player(s).level().getBlockState(pos).isAir());
			check(gone, "the note block must be absorbed after a " + ABSORB_HOLD_TICKS + "-tick hold");
			check(channelStarted[0], "no channel state arrived from the server");
		}
	}

	private static void serverLacksMod(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos pos) {
		ServerPlayNetworking.PlayPayloadHandler<?> handler = sp.getServer().computeOnServer(
				s -> ServerPlayNetworking.unregisterGlobalReceiver(AbsorbStartPayload.TYPE.id()));
		check(handler != null, "the absorb_start receiver was not registered");
		try {
			waitForClientChannel(ctx, false);
			expectVanillaUse(ctx, sp, pos, "server without the channel");
		} finally {
			sp.getServer().runOnServer(s -> reRegister(handler));
			waitForClientChannel(ctx, true);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void reRegister(ServerPlayNetworking.PlayPayloadHandler<?> handler) {
		ServerPlayNetworking.registerGlobalReceiver(AbsorbStartPayload.TYPE, (ServerPlayNetworking.PlayPayloadHandler) handler);
	}

	/** Holds use briefly; vanilla use must cycle the note and no absorb heartbeat may go out. */
	private static void expectVanillaUse(ClientGameTestContext ctx, TestSingleplayerContext sp, BlockPos pos, String what) {
		expectNoGesture(ctx, what);
		int before = note(sp, pos);
		long heartbeatsBefore = heartbeats(ctx);
		ctx.getInput().holdKeyFor(o -> o.keyUse, VANILLA_HOLD_TICKS);
		ctx.waitTicks(5);
		int after = note(sp, pos);
		check(after != before, what + ": vanilla use did not happen (note " + before + " → " + after + ")");
		check(heartbeats(ctx) == heartbeatsBefore, what + ": an absorb heartbeat was sent");
	}

	private static void expectNoGesture(ClientGameTestContext ctx, String what) {
		ctx.runOnClient(mc -> check(!AbsorbInput.shouldIntercept(mc), what + ": vanilla use must not be intercepted"));
	}

	private static void waitForClientChannel(ClientGameTestContext ctx, boolean present) {
		ctx.waitFor(mc -> ClientPlayNetworking.canSend(AbsorbStartPayload.TYPE) == present, 20 * 10);
	}

	private static long heartbeats(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> AbsorbInput.heartbeatsSent());
	}

	private static int note(TestSingleplayerContext sp, BlockPos pos) {
		return sp.getServer().computeOnServer(s -> {
			BlockState state = player(s).level().getBlockState(pos);
			check(state.is(Blocks.NOTE_BLOCK), "the note block is gone: " + state);
			return state.getValue(NoteBlock.NOTE);
		});
	}

	/**
	 * Mode on the server (a server sync, when installed, sends the same) and in the client state (so the test does
	 * not depend on the world-state sync of another package).
	 */
	private static void setMode(ClientGameTestContext ctx, TestSingleplayerContext sp, boolean on) {
		sp.getServer().runOnServer(s -> AbsorbWorldSettings.setEnabled(s, on));
		ctx.runOnClient(mc -> ClientState.setWorld(new WorldStatePayload(on, true, true, 20)));
		ctx.waitTick();
		ctx.runOnClient(mc -> check(ClientState.modeEnabled() == on, "client mode is not " + on));
	}

	/** The same single source on both sides (the client list is set directly: no dependency on the sources sync). */
	private static void setSources(ClientGameTestContext ctx, TestSingleplayerContext sp, SourceDefinition source) {
		sp.getServer().runOnServer(s -> SourceRegistry.set(List.of(source)));
		ctx.runOnClient(mc -> ClientState.setSources(List.of(SourceSummary.of(source))));
	}

	private static SourceDefinition source(Block block) {
		return TestSupport.blockSource(SOURCE_ID.getPath(), BuiltInRegistries.BLOCK.getKey(block), 3, List.of(), List.of(), List.of(), List.of());
	}

	private static ServerPlayer player(MinecraftServer s) {
		return s.getPlayerList().getPlayers().get(0);
	}

	private static void resetHud(Minecraft mc) {
		mc.gui.hud.clearTitles();
		mc.gui.hud.setOverlayMessage(Component.literal(SENTINEL), false);
	}

	private static @Nullable String text(@Nullable Object component) {
		return component instanceof Component c ? c.getString() : null;
	}

	/** A private Hud field (Mojang names at runtime on 26.x): overlayMessageString, title, subtitle. */
	private static @Nullable Object hudField(Minecraft mc, String name) {
		try {
			Field f = Hud.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(mc.gui.hud);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("cannot read Hud." + name, e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
