package dev.absorbaholic.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.absorb.AbsorbRules;
import dev.absorbaholic.client.ClientState;
import dev.absorbaholic.client.screen.TraitsScreen;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.ColorMix;
import dev.absorbaholic.net.ChannelStatePayload;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceSummary;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * The absorb HUD, drawn right after the crosshair: a small hint box to the right of the crosshair while sneaking
 * and looking at a block, fluid or mob in reach (source name in its tier color, trait and weakness, or {@code ???}
 * when nobody in this world has absorbed that source yet; plus one status line: needs low health, protected, not a
 * source block, already maxed or empty your hand; "not absorbable" for targets without a source) and a progress
 * ring around the crosshair while the server channels an absorption. Nothing when hints are off, the mode is off,
 * in creative / spectator, in third person, with a screen open or with the HUD hidden (F1).
 *
 * <p>The hint is computed once per client tick (one raycast); rendering only draws the cached lines.
 */
public final class AbsorbHud {
	public static final Identifier ELEMENT_ID = Absorbaholic.id("absorb_hint");
	/** Hint box: gap between crosshair centre and box text, line height, max text width. */
	private static final int HINT_OFFSET_X = 20;
	private static final int LINE_HEIGHT = 10;
	private static final int MAX_HINT_WIDTH = AbsorbCaps.HUD_HINT_MAX_WIDTH;
	/** Progress ring radii around the crosshair centre (GUI pixels). */
	private static final float RING_INNER = 9.5F;
	private static final float RING_OUTER = 11.5F;
	/** Ticks the full ring stays visible after COMPLETED. */
	private static final int COMPLETED_FLASH_TICKS = 6;
	/** A STARTED channel with no news this long past its end is treated as gone (lost packet). */
	private static final int STALE_TICKS = 20;

	/** One hint line. {@code color} is ARGB. */
	public record Line(Component text, int color) {}

	/** The hint for the current target: lines top to bottom, the accent (tier / neutral) color and the ring color. */
	public record Hint(List<Line> lines, int accent, int ringColor) {
		/** All lines as plain strings (tests, narration). */
		public List<String> strings() {
			return lines.stream().map(l -> l.text().getString()).toList();
		}
	}

	private static @Nullable Hint current;
	private static @Nullable ChannelStatePayload trackedChannel;
	private static int ticksSinceChannelUpdate;
	private static int lastRingColor = 0xFF000000 | ColorMix.DEFAULT;

	private AbsorbHud() {}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ELEMENT_ID, AbsorbHud::extract);
		ClientTickEvents.END_CLIENT_TICK.register(AbsorbHud::tick);
	}

	/** The hint computed on the last client tick, or null when none is shown. */
	public static @Nullable Hint currentHint() {
		return current;
	}

	private static void tick(Minecraft mc) {
		try {
			current = compute(mc);
		} catch (RuntimeException e) {
			current = null;
			Absorbaholic.LOGGER.debug("Absorb hint failed", e);
		}
		if (current != null) lastRingColor = current.ringColor();
		ChannelStatePayload channel = ClientState.channel();
		if (channel != trackedChannel) {
			trackedChannel = channel;
			ticksSinceChannelUpdate = 0;
		} else if (ticksSinceChannelUpdate < 10_000) {
			ticksSinceChannelUpdate++;
		}
	}

	/** The hint for what the local player is looking at now, or null when nothing should be shown. */
	public static @Nullable Hint compute(Minecraft mc) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) return null;
		if (!ClientState.modeEnabled() || !ClientState.hintsEnabled()) return null;
		if (!player.isShiftKeyDown() || player.isSpectator() || player.isCreative() || !player.isAlive()) return null;

		HitResult hit = mc.hitResult;
		if (hit instanceof EntityHitResult entityHit && hit.getType() == HitResult.Type.ENTITY) {
			Entity entity = entityHit.getEntity();
			return entity.isAlive() ? entityHint(player, entity) : null;
		}
		HitResult pick = player.pick(player.blockInteractionRange(), 1.0F, true);
		if (pick instanceof BlockHitResult blockHit && pick.getType() == HitResult.Type.BLOCK) {
			return blockHint(player, level, blockHit.getBlockPos());
		}
		return null;
	}

	private static Hint entityHint(LocalPlayer player, Entity entity) {
		Optional<SourceSummary> source = entity instanceof LivingEntity
				? ClientState.sources().forEntity(entity.getType())
				: Optional.empty();
		if (source.isEmpty()) return notAbsorbable(entity.getName());
		List<Line> status = new ArrayList<>();
		if (entity instanceof LivingEntity living
				&& living.getHealth() > AbsorbCaps.MOB_HEALTH_THRESHOLD * living.getMaxHealth()) {
			String percent = String.format(Locale.ROOT, "%d", Math.round(AbsorbCaps.MOB_HEALTH_THRESHOLD * 100));
			status.add(new Line(Component.translatable("absorbaholic.hint.needs_health", percent), TraitsScreen.WARNING_COLOR));
		}
		return sourceHint(player, source.get(), status);
	}

	private static Hint blockHint(LocalPlayer player, ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		FluidState fluid = state.getFluidState();
		boolean liquid = state.getBlock() instanceof LiquidBlock;
		Optional<SourceSummary> source = liquid ? ClientState.sources().forFluid(fluid) : ClientState.sources().forBlock(state);
		if (source.isEmpty()) return notAbsorbable(state.getBlock().getName());
		List<Line> status = new ArrayList<>();
		if (!liquid && AbsorbRules.isProtected(level, pos, state)) {
			status.add(new Line(Component.translatable("absorbaholic.hint.protected"), TraitsScreen.WARNING_COLOR));
		} else if (liquid && !fluid.isSource()) {
			status.add(new Line(Component.translatable("absorbaholic.hint.flowing"), TraitsScreen.WARNING_COLOR));
		}
		return sourceHint(player, source.get(), status);
	}

	private static Hint notAbsorbable(Component name) {
		List<Line> lines = List.of(
				new Line(name, TraitsScreen.MUTED_COLOR),
				new Line(Component.translatable("absorbaholic.hint.not_absorbable"), TraitsScreen.DIM_COLOR));
		return new Hint(lines, TraitsScreen.DIM_COLOR, 0xFF000000 | ColorMix.DEFAULT);
	}

	private static Hint sourceHint(LocalPlayer player, SourceSummary source, List<Line> status) {
		Optional<TraitEntry> owned = ClientState.ownTraits().get(source.id());
		boolean known = owned.isPresent() || ClientState.isDiscovered(source.id());
		int tier = TraitsScreen.tierColor(source.tier());
		List<Line> lines = new ArrayList<>();
		lines.add(new Line(TraitsScreen.sourceName(source), tier));
		Component unknown = Component.translatable("absorbaholic.hint.unknown");
		Component trait = known ? TraitsScreen.traitText(source, owned.map(TraitEntry::traitLevel).orElse(0)) : unknown;
		Component weakness = known ? TraitsScreen.weaknessText(source, owned.map(TraitEntry::weaknessLevel).orElse(0)) : unknown;
		lines.add(new Line(Component.translatable("absorbaholic.hint.trait", trait), TraitsScreen.TRAIT_COLOR));
		lines.add(new Line(Component.translatable("absorbaholic.hint.weakness", weakness), TraitsScreen.WEAKNESS_COLOR));
		if (status.isEmpty() && owned.isPresent() && owned.get().traitLevel() >= source.maxLevel()) {
			status.add(new Line(Component.translatable("absorbaholic.hint.maxed"), TraitsScreen.WARNING_COLOR));
		}
		if (status.isEmpty() && !player.getMainHandItem().isEmpty()) {
			status.add(new Line(Component.translatable("absorbaholic.hint.empty_hand"), TraitsScreen.MUTED_COLOR));
		}
		lines.addAll(status);
		return new Hint(List.copyOf(lines), tier, 0xFF000000 | source.color());
	}

	// ---- rendering ----

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui.screen() != null || !mc.options.getCameraType().isFirstPerson()) return;
		int cx = graphics.guiWidth() / 2;
		int cy = graphics.guiHeight() / 2;
		float progress = channelProgress(delta.getGameTimeDeltaPartialTick(false));
		if (progress >= 0) drawRing(graphics, cx, cy, progress, lastRingColor);
		Hint hint = current;
		if (hint != null) drawHint(graphics, mc, hint, cx, cy);
	}

	/** Channel progress 0..1, or -1 when no ring should be drawn. */
	public static float channelProgress(float partialTick) {
		ChannelStatePayload channel = trackedChannel;
		if (channel == null || channel != ClientState.channel()) return -1;
		int total = Math.max(1, channel.totalTicks());
		return switch (channel.status()) {
			case STARTED, PROGRESS -> {
				float elapsed = channel.elapsedTicks() + ticksSinceChannelUpdate + partialTick;
				yield elapsed > total + STALE_TICKS ? -1 : Mth.clamp(elapsed / total, 0.0F, 1.0F);
			}
			case COMPLETED -> ticksSinceChannelUpdate < COMPLETED_FLASH_TICKS ? 1.0F : -1;
			case CANCELLED -> -1;
		};
	}

	/** A pixel ring around the crosshair: the done part in the source color, the rest as a faint track. */
	private static void drawRing(GuiGraphicsExtractor graphics, int cx, int cy, float progress, int color) {
		int reach = (int) Math.ceil(RING_OUTER);
		int track = 0x70000000;
		// lift dark source colors (obsidian, dragon) so the ring reads on any background
		int bright = ColorMix.mix(new int[] {color & 0xFFFFFF, 0xFFFFFF}, new double[] {0.55, 0.45});
		int done = ARGB.color(240, bright);
		for (int dy = -reach; dy < reach; dy++) {
			for (int dx = -reach; dx < reach; dx++) {
				float px = dx + 0.5F;
				float py = dy + 0.5F;
				float dist = Mth.sqrt(px * px + py * py);
				if (dist < RING_INNER || dist > RING_OUTER) continue;
				// clockwise from 12 o'clock
				float angle = (float) (Math.atan2(px, -py) / (2 * Math.PI));
				if (angle < 0) angle += 1.0F;
				int x = cx + dx;
				int y = cy + dy;
				graphics.fill(x, y, x + 1, y + 1, angle <= progress ? done : track);
			}
		}
	}

	private static void drawHint(GuiGraphicsExtractor graphics, Minecraft mc, Hint hint, int cx, int cy) {
		Font font = mc.font;
		int x = cx + HINT_OFFSET_X;
		int maxWidth = Math.min(MAX_HINT_WIDTH, graphics.guiWidth() - x - 6);
		if (maxWidth < 30) return;
		int width = 0;
		for (Line line : hint.lines()) width = Math.max(width, Math.min(maxWidth, font.width(line.text())));
		int height = hint.lines().size() * LINE_HEIGHT - 1;
		int top = cy - height / 2;
		int background = mc.options.getBackgroundColor(0x90000000);
		if (ARGB.alpha(background) > 0) graphics.fill(x - 4, top - 3, x + width + 3, top + height + 2, background);
		graphics.fill(x - 4, top - 3, x - 3, top + height + 2, hint.accent());
		int y = top;
		for (Line line : hint.lines()) {
			graphics.text(font, TraitsScreen.clip(font, line.text(), maxWidth), x, y, line.color(), true);
			y += LINE_HEIGHT;
		}
	}
}
