package dev.absorbaholic.trait.behavior;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.Hook;
import dev.absorbaholic.trait.MovementFlags;
import dev.absorbaholic.trait.MovementFlagsHolder;
import dev.absorbaholic.trait.MovementState;
import dev.absorbaholic.trait.TraitEngine;
import net.fabricmc.fabric.api.entity.event.v1.EntityElytraEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * {@code absorbaholic:glide} (phantom), trigger {@code air_jump} once no air-jump charges are left:
 * {@code max_ticks} [L]. Elytra-style fall flying without an elytra.
 * <ul>
 * <li>{@link MovementFlags#GLIDE} makes {@code canGlide} true on both sides through Fabric's
 *     {@code EntityElytraEvents.CUSTOM} (registered by the engine; never a canGlide mixin).</li>
 * <li>The server alone decides when a glide may run: the trigger starts it ({@code startFallFlying}, synced to the
 *     client), and this class's {@code EntityElytraEvents.ALLOW} listener refuses any other elytra-less glide of a
 *     player with an active glide entry (a client's own START_FALL_FLYING, a restart), so vanilla stops it. On a
 *     modded client the same listener keeps the LocalPlayer from starting the glide itself (no local start that the
 *     server then cancels while air-jump charges remain): it glides once the server's flag arrives.</li>
 * <li>It ends on landing, in water or lava, when riding, or after {@code max_ticks}; after that no new glide until
 *     the player touches the ground. Firework boosts and fly_into_wall damage work as with an elytra. A player
 *     wearing a real glider is left to vanilla.</li>
 * </ul>
 */
public final class GlideBehavior implements Behavior<GlideBehavior.Params> {
	/** One glide per player, whichever glide entry started it. */
	private static final Identifier STATE_KEY = Absorbaholic.id("glide/state");

	public record Params(LevelValue maxTicks, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("max_ticks").forGetter(Params::maxTicks),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));

		/** Glide length at {@code level} (0 = inactive). */
		public int maxTicksAt(int level) {
			return AbilitySupport.ticksOf(AbilitySupport.at(maxTicks, level));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("glide", Params.CODEC, new GlideBehavior());

	/** Registers the ALLOW gate, both sides (common init). */
	static void registerEvents() {
		EntityElytraEvents.ALLOW.register(GlideBehavior::allowGlide);
	}

	/**
	 * The glide gate. Server: for a player with an active glide entry, only our own trigger may run an elytra-less
	 * glide. Client (the modded owner's LocalPlayer, whose synced flags include GLIDE): it never starts an elytra-less
	 * glide itself, because only the server knows whether an air-jump charge or the glide wins the press; the server's
	 * trigger starts it and the synced fall-flying flag makes the client glide one round trip later. Without this the
	 * client began gliding at every airborne jump press (canGlide is true through CUSTOM) and the server stopped it
	 * again: a visible flicker whenever air-jump charges remained. Client-side, canGlide is only consulted by
	 * {@code tryToStartFallFlying} (whose own check requires not gliding yet), so a running glide is never affected.
	 */
	private static boolean allowGlide(LivingEntity entity) {
		if (!(entity instanceof Player player)) return true;
		try {
			if (!((MovementFlagsHolder) player).absorbaholic$movement().has(MovementFlags.GLIDE) || hasRealGlider(player)) return true;
			if (player.level().isClientSide()) return player.isFallFlying();
			if (!(player instanceof ServerPlayer serverPlayer) || !hasGlideEntry(serverPlayer)) return true;
			return PlayerData.runtime(serverPlayer).behaviorState.get(STATE_KEY) instanceof GlideState st && st.gliding;
		} catch (RuntimeException e) {
			Absorbaholic.LOGGER.debug("Absorbaholic glide gate failed", e);
			return true;
		}
	}

	@Override
	public int tickInterval(Params params) {
		return 1;
	}

	@Override
	public MovementState movement(ActiveBehavior<Params> self) {
		return MovementState.of(MovementFlags.GLIDE);
	}

	@Override
	public boolean onAirJump(ActiveBehavior<Params> self, ServerPlayer player) {
		int maxTicks = self.params().maxTicksAt(self.level());
		if (maxTicks <= 0 || hasRealGlider(player)) return false;
		GlideState st = state(player);
		if (st.gliding || st.spent || player.isFallFlying()) return false;
		if (player.isInWater() || player.isInLava() || player.isPassenger() || player.onGround() || player.getAbilities().flying
				|| player.hasEffect(MobEffects.LEVITATION)) {
			return false;
		}
		if (AirJumpBehavior.chargesLeft(player) || !self.params().condition().test(player)) return false;
		st.gliding = true;
		st.spent = true;
		st.ticks = 0;
		st.maxTicks = maxTicks;
		player.startFallFlying();
		return true;
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		if (!(PlayerData.runtime(player).behaviorState.get(STATE_KEY) instanceof GlideState st)) return;
		if (player.onGround()) st.spent = false;
		if (!st.gliding) return;
		if (!player.isFallFlying()) {
			st.gliding = false;
			return;
		}
		st.ticks++;
		if (st.ticks >= st.maxTicks || player.isInWater() || player.isInLava() || player.isPassenger()) stop(player, st);
	}

	@Override
	public void onDeactivate(ActiveBehavior<Params> self, ServerPlayer player) {
		if (PlayerData.runtime(player).behaviorState.remove(STATE_KEY) instanceof GlideState st && st.gliding) stop(player, st);
	}

	private static void stop(ServerPlayer player, GlideState st) {
		st.gliding = false;
		if (player.isFallFlying() && !hasRealGlider(player)) player.stopFallFlying();
	}

	private static GlideState state(ServerPlayer player) {
		return (GlideState) PlayerData.runtime(player).behaviorState.compute(STATE_KEY, (k, v) -> v instanceof GlideState g ? g : new GlideState());
	}

	private static boolean hasGlideEntry(ServerPlayer player) {
		for (ActiveBehavior<?> a : TraitEngine.active(player).forHook(Hook.AIR_JUMP)) {
			if (a.type() == TYPE) return true;
		}
		return false;
	}

	/** An equipped item vanilla can glide with (elytra): vanilla rules then, not ours. */
	static boolean hasRealGlider(LivingEntity entity) {
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			if (LivingEntity.canGlideUsing(entity.getItemBySlot(slot), slot)) return true;
		}
		return false;
	}

	/** The player's glide: running, ticks so far, its length, and "used this airtime". */
	static final class GlideState {
		boolean gliding;
		boolean spent;
		int ticks;
		int maxTicks;
	}
}
