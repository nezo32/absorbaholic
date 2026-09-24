package dev.absorbaholic.absorb;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelStacking;
import dev.absorbaholic.core.MutationRoll;
import dev.absorbaholic.net.AbsorbCancelPayload;
import dev.absorbaholic.net.AbsorbNetworking;
import dev.absorbaholic.net.AbsorbStartPayload;
import dev.absorbaholic.net.ChannelStatePayload;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerRuntime;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.world.AbsorbWorldSettings;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server side of the absorb gesture (ARCHITECTURE §6.2). The client sends {@link AbsorbStartPayload} every tick
 * while it holds the use key on a target (a heartbeat) and {@link AbsorbCancelPayload} on release. A new target is
 * validated once; a valid one starts an {@link AbsorbChannel} (in {@code PlayerRuntime.channel}) that is
 * re-validated every tick and completes after {@link AbsorbCaps#CHANNEL_TICKS} through {@link AbsorbService#complete}.
 * A refused or ended gesture is remembered until the key is released (or the heartbeat stops), so a refusal is shown
 * once and a finished channel never restarts by itself. UseBlock / UseEntity interactions are suppressed while a
 * modded player's gesture targets an absorbable, covering use packets that race the client's own cancel.
 *
 * <p>All state is server-thread only. Every C2S input is untrusted: positions are range-checked before any lookup,
 * entity ids are resolved in the player's own level, and a player validates at most one new target per tick.
 */
public final class AbsorbHandler {
	/** Particles and the rising sound play every this-many channel ticks. */
	public static final int EFFECT_INTERVAL = 5;
	/** A PROGRESS state is sent every this-many channel ticks (the client HUD resyncs with it). */
	public static final int PROGRESS_INTERVAL = 10;

	/** Per-player gesture memory (UUID → gesture), server thread only. */
	private static final Map<UUID, Gesture> GESTURES = new HashMap<>();
	private static boolean loggedFailure;

	private AbsorbHandler() {}

	/** The gesture memory of one player: a target to ignore until released, and rate limiting. */
	private static final class Gesture {
		@Nullable AbsorbTarget ignored;
		long ignoredSeen;
		long validatedAt = Long.MIN_VALUE;
	}

	/** A validated target: its source, the source's display name, and the living entity (entities only). */
	public record Resolved(AbsorbTarget target, SourceDefinition source, Component name, @Nullable LivingEntity entity) {
		/** The centre of the target, where the channel particles start. */
		public Vec3 center() {
			return entity != null ? entity.getBoundingBox().getCenter() : Vec3.atCenterOf(target.pos());
		}
	}

	/**
	 * Result of {@link #validate}: either {@code resolved} (valid), or a failure with an optional refusal reason
	 * ({@code reasonKey} + {@code reason}); a failure without a reason is silent (key released, target lost).
	 */
	public record Check(@Nullable Resolved resolved, @Nullable String reasonKey, @Nullable Component reason) {
		static final Check SILENT = new Check(null, null, null);

		static Check refuse(String key, Object... args) {
			return new Check(null, key, AbsorbFeedback.reason(key, args));
		}

		public boolean ok() {
			return resolved != null;
		}
	}

	/** What one channel tick did. {@code reasonKey} is set for a CANCELLED channel with a refusal reason. */
	public record TickResult(ChannelStatePayload.Status status, @Nullable String reasonKey) {
		static final TickResult NONE = new TickResult(null, null);
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(AbsorbStartPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			safely(() -> start(player, payload.target(), now(context.server())));
		});
		ServerPlayNetworking.registerGlobalReceiver(AbsorbCancelPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			safely(() -> cancel(player));
		});
		ServerTickEvents.END_SERVER_TICK.register(AbsorbHandler::tickAll);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> GESTURES.remove(handler.player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> GESTURES.clear());
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			return suppressesUse(sp, hit.getBlockPos(), null, canReceive(sp)) ? InteractionResult.FAIL : InteractionResult.PASS;
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			return suppressesUse(sp, null, entity, canReceive(sp)) ? InteractionResult.FAIL : InteractionResult.PASS;
		});
	}

	/** The absorb clock: overworld game time (shared by every dimension, unaffected by /time set). */
	public static long now(MinecraftServer server) {
		return server.overworld().getGameTime();
	}

	// ---- gesture input -----------------------------------------------------------------------------------------

	/**
	 * A start / heartbeat for {@code target} at game time {@code now}. The same target as the running channel only
	 * refreshes the heartbeat; a new target is validated (at most once per tick) and either starts a channel or is
	 * refused (the reason is shown once per gesture). Returns the validation, or null when nothing was validated.
	 */
	public static @Nullable Check start(ServerPlayer player, AbsorbTarget target, long now) {
		PlayerRuntime runtime = PlayerData.runtime(player);
		AbsorbChannel channel = runtime.channel;
		if (channel != null && channel.target().equals(target)) {
			runtime.channel = channel.held(now);
			return null;
		}
		Gesture gesture = GESTURES.computeIfAbsent(player.getUUID(), id -> new Gesture());
		if (target.equals(gesture.ignored) && now - gesture.ignoredSeen <= AbsorbNetworking.HEARTBEAT_TIMEOUT) {
			gesture.ignoredSeen = now;
			return null;
		}
		if (gesture.validatedAt == now) return null; // rate limit: one new target per tick
		gesture.validatedAt = now;
		if (channel != null) {
			runtime.channel = null;
			sendState(player, ChannelStatePayload.Status.CANCELLED, channel.elapsed(now));
		}
		Check check = validate(player, target, null, now);
		if (!check.ok()) {
			// a refusal is shown once and holds until release; a silent failure (e.g. the server's view of the
			// player's aim lags a tick behind) is simply re-validated on the next heartbeat
			if (check.reason() != null) {
				ignore(gesture, target, now);
				AbsorbFeedback.refused(player, check.reason());
				sendState(player, ChannelStatePayload.Status.CANCELLED, 0);
			}
			return check;
		}
		gesture.ignored = null;
		runtime.channel = new AbsorbChannel(target, check.resolved().source().id(), now, now);
		sendState(player, ChannelStatePayload.Status.STARTED, 0);
		return check;
	}

	/** The use key was released: end the running channel (silently) and forget the gesture. */
	public static void cancel(ServerPlayer player) {
		Gesture gesture = GESTURES.get(player.getUUID());
		if (gesture != null) gesture.ignored = null;
		PlayerRuntime runtime = PlayerData.runtime(player);
		AbsorbChannel channel = runtime.channel;
		if (channel == null) return;
		runtime.channel = null;
		sendState(player, ChannelStatePayload.Status.CANCELLED, 0);
	}

	private static void ignore(Gesture gesture, AbsorbTarget target, long now) {
		gesture.ignored = target;
		gesture.ignoredSeen = now;
	}

	// ---- channel ticking ---------------------------------------------------------------------------------------

	private static void tickAll(MinecraftServer server) {
		long now = now(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerRuntime runtime = player.getAttached(PlayerData.RUNTIME);
			if (runtime == null || runtime.channel == null) continue;
			try {
				tick(player, now);
			} catch (RuntimeException e) {
				runtime.channel = null; // never retry a failing channel every tick
				logFailure(e);
			}
		}
	}

	/** One channel tick with the player's own random for the mutation roll. */
	public static TickResult tick(ServerPlayer player, long now) {
		return tick(player, now, player.getRandom()::nextDouble);
	}

	/**
	 * One channel tick at game time {@code now} (idempotent within a tick: progress is measured from the start time).
	 * Cancels on a missed heartbeat or any failed check, plays the channel effects, and completes the absorption
	 * with a roll drawn from {@code random} once {@link AbsorbCaps#CHANNEL_TICKS} have passed.
	 */
	public static TickResult tick(ServerPlayer player, long now, MutationRoll.Uniform random) {
		PlayerRuntime runtime = PlayerData.runtime(player);
		AbsorbChannel channel = runtime.channel;
		if (channel == null) return TickResult.NONE;
		Gesture gesture = GESTURES.computeIfAbsent(player.getUUID(), id -> new Gesture());
		int elapsed = channel.elapsed(now);
		if (now - channel.lastHeldTick() > AbsorbNetworking.HEARTBEAT_TIMEOUT) {
			runtime.channel = null;
			gesture.ignored = null;
			sendState(player, ChannelStatePayload.Status.CANCELLED, elapsed);
			return new TickResult(ChannelStatePayload.Status.CANCELLED, null);
		}
		Check check = validate(player, channel.target(), channel.source(), now);
		if (!check.ok()) {
			runtime.channel = null;
			ignore(gesture, channel.target(), now);
			if (check.reason() != null) AbsorbFeedback.refused(player, check.reason());
			sendState(player, ChannelStatePayload.Status.CANCELLED, elapsed);
			return new TickResult(ChannelStatePayload.Status.CANCELLED, check.reasonKey());
		}
		if (elapsed >= AbsorbCaps.CHANNEL_TICKS) {
			runtime.channel = null;
			ignore(gesture, channel.target(), now);
			AbsorbService.Result result = AbsorbService.complete(player, check.resolved(), MutationRoll.roll(random), now);
			if (!result.success()) {
				sendState(player, ChannelStatePayload.Status.CANCELLED, elapsed);
				return new TickResult(ChannelStatePayload.Status.CANCELLED, AbsorbFeedback.REFUSE_MAX_LEVEL);
			}
			return new TickResult(ChannelStatePayload.Status.COMPLETED, null);
		}
		if (elapsed > 0 && elapsed % EFFECT_INTERVAL == 0) AbsorbFeedback.channelTick(player, check.resolved().center(), elapsed);
		if (elapsed > 0 && elapsed % PROGRESS_INTERVAL == 0) sendState(player, ChannelStatePayload.Status.PROGRESS, elapsed);
		return new TickResult(ChannelStatePayload.Status.PROGRESS, null);
	}

	// ---- validation --------------------------------------------------------------------------------------------

	/**
	 * Every rule of absorbing {@code target} right now: mode ON, survival / adventure, pose (sneaking, empty main
	 * hand), cooldown, the target still resolves (to {@code expectedSource} if given), in range, seen by a server
	 * raycast, world protection, no container contents, mob health, trait not maxed.
	 */
	public static Check validate(ServerPlayer player, AbsorbTarget target, @Nullable Identifier expectedSource, long now) {
		MinecraftServer server = player.level().getServer();
		if (!AbsorbWorldSettings.isEnabled(server)) return Check.refuse(AbsorbFeedback.REFUSE_DISABLED);
		if (player instanceof FakePlayer || !player.gameMode().isSurvival() || !AbsorbRules.poseAllows(player)) return Check.SILENT;
		long cooldown = PlayerData.runtime(player).absorbCooldownUntil - now;
		if (cooldown > 0) return Check.refuse(AbsorbFeedback.REFUSE_COOLDOWN, (cooldown + 19) / 20);

		Check check = target.kind() == AbsorbTarget.Kind.ENTITY
				? validateEntity(player, target, expectedSource)
				: validateBlock(player, target, expectedSource);
		if (!check.ok()) return check;

		SourceDefinition source = check.resolved().source();
		int traitLevel = PlayerData.traits(player).get(source.id()).map(TraitEntry::traitLevel).orElse(0);
		if (!LevelStacking.canAbsorb(traitLevel, source.maxLevel())) {
			return Check.refuse(AbsorbFeedback.REFUSE_MAX_LEVEL, AbsorbFeedback.traitName(source));
		}
		return check;
	}

	private static Check validateBlock(ServerPlayer player, AbsorbTarget target, @Nullable Identifier expected) {
		ServerLevel level = player.level();
		BlockPos pos = target.pos();
		if (!player.isWithinBlockInteractionRange(pos, AbsorbCaps.CHANNEL_RANGE_TOLERANCE) || !level.isLoaded(pos)) return Check.SILENT;
		BlockState state = level.getBlockState(pos);
		boolean fluid = target.kind() == AbsorbTarget.Kind.FLUID;
		Optional<SourceDefinition> source;
		if (fluid) {
			FluidState fluidState = state.getFluidState();
			if (!(state.getBlock() instanceof LiquidBlock) || !fluidState.isSource()) return Check.SILENT;
			source = SourceRegistry.forFluid(fluidState);
		} else {
			if (state.getBlock() instanceof LiquidBlock) return Check.SILENT;
			source = SourceRegistry.forBlock(state);
		}
		if (source.isEmpty() || expected != null && !source.get().id().equals(expected)) return Check.SILENT;
		if (!seesBlock(player, pos, fluid)) return Check.SILENT;
		if (AbsorbRules.isProtected(level, pos, state)) return Check.refuse(AbsorbFeedback.REFUSE_PROTECTED);
		if (holdsItems(level.getBlockEntity(pos))) return Check.refuse(AbsorbFeedback.REFUSE_CONTAINER);
		return new Check(new Resolved(target, source.get(), AbsorbFeedback.sourceName(source.get()), null), null, null);
	}

	private static Check validateEntity(ServerPlayer player, AbsorbTarget target, @Nullable Identifier expected) {
		LivingEntity entity = livingTarget(player.level(), target.entityId());
		if (entity == null || !player.isWithinEntityInteractionRange(entity, AbsorbCaps.CHANNEL_RANGE_TOLERANCE)) return Check.SILENT;
		Optional<SourceDefinition> source = SourceRegistry.forEntity(entity.getType());
		if (source.isEmpty() || expected != null && !source.get().id().equals(expected)) return Check.SILENT;
		if (!seesEntity(player, entity)) return Check.SILENT;
		if (entity.getHealth() > AbsorbCaps.MOB_HEALTH_THRESHOLD * entity.getMaxHealth()) {
			return Check.refuse(AbsorbFeedback.REFUSE_MOB_HEALTH, Math.round(AbsorbCaps.MOB_HEALTH_THRESHOLD * 100.0F));
		}
		return new Check(new Resolved(target, source.get(), AbsorbFeedback.sourceName(source.get()), entity), null, null);
	}

	/** The living, non-player entity {@code id} in {@code level}; a dragon part id resolves to its dragon. */
	@SuppressWarnings("deprecation") // getEntityOrPart: the only lookup that knows dragon parts, same in 26.2 / 26.3
	public static @Nullable LivingEntity livingTarget(ServerLevel level, int id) {
		Entity entity = level.getEntityOrPart(id);
		if (entity instanceof EnderDragonPart part) entity = part.parentMob;
		if (!(entity instanceof LivingEntity living) || living instanceof Player || !living.isAlive() || living.isRemoved()) return null;
		return living;
	}

	private static boolean seesBlock(ServerPlayer player, BlockPos pos, boolean fluid) {
		HitResult hit = player.pick(player.blockInteractionRange() + AbsorbCaps.CHANNEL_RANGE_TOLERANCE, 1.0F, fluid);
		return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(pos);
	}

	private static boolean seesEntity(ServerPlayer player, LivingEntity entity) {
		double range = player.entityInteractionRange() + AbsorbCaps.CHANNEL_RANGE_TOLERANCE;
		Vec3 from = player.getEyePosition();
		Vec3 reach = player.getViewVector(1.0F).scale(range);
		AABB box = player.getBoundingBox().expandTowards(reach).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, from, from.add(reach), box,
				e -> !e.isSpectator() && e.isPickable() && (e == entity || e instanceof EnderDragonPart part && part.parentMob == entity),
				range * range);
		if (hit == null) return false;
		HitResult block = player.pick(range, 1.0F, false);
		return block.getType() != HitResult.Type.BLOCK
				|| block.getLocation().distanceToSqr(from) >= hit.getLocation().distanceToSqr(from);
	}

	/** Block entities whose items a silent removal would destroy (D8: refuse instead of losing them). */
	static boolean holdsItems(@Nullable BlockEntity blockEntity) {
		if (blockEntity instanceof Container container) return !container.isEmpty();
		if (blockEntity instanceof LecternBlockEntity lectern) return lectern.hasBook();
		if (blockEntity instanceof CampfireBlockEntity campfire) return campfire.getItems().stream().anyMatch(s -> !s.isEmpty());
		return false;
	}

	// ---- use suppression ---------------------------------------------------------------------------------------

	/**
	 * True when a vanilla use interaction must be suppressed: the (modded) player is channeling, or makes a valid
	 * absorb gesture (mode ON, survival / adventure, sneaking with an empty hand) at an absorbable block or entity.
	 * The cooldown is deliberately not checked: the client intercepts regardless and the server refuses.
	 */
	public static boolean suppressesUse(ServerPlayer player, @Nullable BlockPos pos, @Nullable Entity entity, boolean modded) {
		if (!modded || player instanceof FakePlayer) return false;
		PlayerRuntime runtime = player.getAttached(PlayerData.RUNTIME);
		if (runtime != null && runtime.channel != null) return true;
		if (!AbsorbWorldSettings.isEnabled(player.level().getServer()) || !player.gameMode().isSurvival() || !AbsorbRules.poseAllows(player)) return false;
		if (pos != null) return SourceRegistry.forBlock(player.level().getBlockState(pos)).isPresent();
		if (entity instanceof EnderDragonPart part) entity = part.parentMob;
		return entity instanceof LivingEntity && !(entity instanceof Player) && SourceRegistry.forEntity(entity.getType()).isPresent();
	}

	// ---- helpers -----------------------------------------------------------------------------------------------

	private static boolean canReceive(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, ChannelStatePayload.TYPE);
	}

	/** Sends the channel state to a modded client. */
	static void sendState(ServerPlayer player, ChannelStatePayload.Status status, int elapsed) {
		if (canReceive(player)) {
			ServerPlayNetworking.send(player, new ChannelStatePayload(status, Math.max(0, elapsed), AbsorbCaps.CHANNEL_TICKS));
		}
	}

	private static void safely(Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			logFailure(e);
		}
	}

	/** The first failure is logged as an error, later ones at debug level (never crashes the tick). */
	private static void logFailure(RuntimeException e) {
		if (!loggedFailure) {
			loggedFailure = true;
			Absorbaholic.LOGGER.error("Absorbaholic absorb handling failed", e);
		} else {
			Absorbaholic.LOGGER.debug("Absorbaholic absorb handling failed", e);
		}
	}
}
