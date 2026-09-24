package dev.absorbaholic.client.input;

import java.util.Optional;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.absorb.AbsorbRules;
import dev.absorbaholic.absorb.AbsorbTarget;
import dev.absorbaholic.client.ClientState;
import dev.absorbaholic.net.AbsorbCancelPayload;
import dev.absorbaholic.net.AbsorbStartPayload;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceSummary;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * The absorb gesture on the client. {@link #shouldIntercept} (MinecraftMixin, HEAD of {@code startUseItem}) swallows
 * vanilla use only while {@link #target} finds something: the server has the mod (it accepts
 * {@link AbsorbStartPayload}), the world mode is ON, {@link AbsorbRules#poseAllows} (sneaking, both hands empty,
 * survival / adventure), no item is in use, and the crosshair target is absorbable per {@link ClientState#sources()},
 * its source not yet maxed in the player's own traits ({@link AbsorbRules#canGrow}), a block neither
 * {@link AbsorbRules#isProtected protected} nor action-restricted, a mob {@link AbsorbRules#weakEnough weak enough}.
 * These mirror the server's use suppression. In every other case vanilla use runs untouched.
 *
 * <p>END_CLIENT_TICK: while the use key is held (no screen open) on a target, {@code AbsorbStartPayload(target)} goes
 * out every tick as the heartbeat. Release, target loss or change, a screen opening or losing the pose sends one
 * {@link AbsorbCancelPayload} (a changed target then starts again with its own heartbeat).
 */
public final class AbsorbInput {
	private static @Nullable AbsorbTarget active;
	private static long heartbeats;
	private static long cancels;
	private static boolean failureLogged;

	private AbsorbInput() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(AbsorbInput::tick);
	}

	/** True when vanilla use must be skipped because the use key drives the absorb gesture right now. Never throws. */
	public static boolean shouldIntercept(Minecraft mc) {
		return target(mc) != null;
	}

	/**
	 * The absorbable target under the crosshair when every gesture precondition holds, else null. A living entity from
	 * {@code mc.hitResult} (at most 25 % health) wins; any other entity under the crosshair means "not ours". Otherwise
	 * a fluid-inclusive pick within block reach: a source fluid with an absorb source is a FLUID target; any other fluid
	 * is looked through with a pick without fluids, whose block is a BLOCK target. Nothing while an item is in use.
	 * Never throws (a failure is logged once and counts as "no target").
	 */
	public static @Nullable AbsorbTarget target(Minecraft mc) {
		try {
			return findTarget(mc);
		} catch (RuntimeException e) {
			if (!failureLogged) {
				failureLogged = true;
				Absorbaholic.LOGGER.error("Absorb target lookup failed; vanilla use proceeds", e);
			}
			return null;
		}
	}

	private static @Nullable AbsorbTarget findTarget(Minecraft mc) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) return null;
		if (!ClientState.modeEnabled() || !AbsorbRules.poseAllows(player)) return null;
		// vanilla never calls startUseItem while an item is in use (a raised shield, eating): no gesture either
		if (player.isUsingItem()) return null;
		if (!ClientPlayNetworking.canSend(AbsorbStartPayload.TYPE)) return null;

		HitResult crosshair = mc.hitResult;
		if (crosshair instanceof EntityHitResult entityHit && crosshair.getType() == HitResult.Type.ENTITY) {
			Entity entity = entityHit.getEntity();
			if (entity instanceof EnderDragonPart part) entity = part.parentMob;
			// a healthy mob keeps its vanilla interaction (horse inventory, sitting pets …): no gesture above 25 %
			if (!(entity instanceof LivingEntity living) || living instanceof Player || !living.isAlive() || !AbsorbRules.weakEnough(living)) {
				return null;
			}
			Optional<SourceSummary> source = ClientState.sources().forEntity(living.getType());
			return source.isPresent() && canGrow(source.get()) ? AbsorbTarget.entity(living.getId()) : null;
		}

		double range = player.blockInteractionRange();
		HitResult withFluids = player.pick(range, 1.0F, true);
		if (withFluids instanceof BlockHitResult fluidHit && withFluids.getType() == HitResult.Type.BLOCK
				&& level.getBlockState(fluidHit.getBlockPos()).getBlock() instanceof LiquidBlock) {
			// Keep a fluid hit only when that fluid is an absorbable source; otherwise look through it (water in the
			// way, or eyes under water) at the block behind, as vanilla use and the server's block raycast do.
			FluidState fluid = level.getFluidState(fluidHit.getBlockPos());
			Optional<SourceSummary> source = fluid.isSource() ? ClientState.sources().forFluid(fluid) : Optional.empty();
			if (source.isPresent()) return canGrow(source.get()) ? AbsorbTarget.fluid(fluidHit.getBlockPos()) : null;
		}
		HitResult hit = player.pick(range, 1.0F, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return null;
		BlockPos pos = blockHit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof LiquidBlock) return null;
		if (AbsorbRules.isProtected(level, pos, state)) return null;
		if (mc.gameMode != null && AbsorbRules.blockActionRestricted(player, level, pos, mc.gameMode.getPlayerMode())) return null;
		Optional<SourceSummary> source = ClientState.sources().forBlock(state);
		return source.isPresent() && canGrow(source.get()) ? AbsorbTarget.block(pos) : null;
	}

	/** A maxed source is never intercepted: its sneak-use stays vanilla (the server would refuse the absorb anyway). */
	private static boolean canGrow(SourceSummary source) {
		int level = ClientState.ownTraits().get(source.id()).map(TraitEntry::traitLevel).orElse(0);
		return AbsorbRules.canGrow(level, source.maxLevel());
	}

	private static void tick(Minecraft mc) {
		AbsorbTarget target = mc.gui.screen() == null && mc.options.keyUse.isDown() ? target(mc) : null;
		if (active != null && !active.equals(target)) {
			if (send(AbsorbCancelPayload.INSTANCE)) cancels++;
			active = null;
		}
		if (target != null && send(new AbsorbStartPayload(target))) {
			active = target;
			heartbeats++;
		}
	}

	/** Sends when the server accepts this payload type (it may have dropped the mod's channels meanwhile). */
	private static boolean send(CustomPacketPayload payload) {
		if (!ClientPlayNetworking.canSend(payload.type())) return false;
		ClientPlayNetworking.send(payload);
		return true;
	}

	/** Forgets the running gesture without sending anything (the connection is gone). */
	public static void reset() {
		active = null;
	}

	/** The target of the gesture in progress (the last heartbeat's), or null. */
	public static @Nullable AbsorbTarget activeTarget() {
		return active;
	}

	/** Heartbeats sent since the game started (diagnostics; the client gametest counts them). */
	public static long heartbeatsSent() {
		return heartbeats;
	}

	/** Cancels sent since the game started (diagnostics; the client gametest counts them). */
	public static long cancelsSent() {
		return cancels;
	}
}
