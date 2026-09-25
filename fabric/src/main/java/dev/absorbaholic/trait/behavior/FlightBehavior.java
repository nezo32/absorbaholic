package dev.absorbaholic.trait.behavior;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * {@code absorbaholic:flight} (dragon egg): creative-style flight in survival / adventure through the abilities
 * packet, so it works on vanilla clients. {@code speed} [L] is the flying speed (vanilla creative 0.05),
 * {@code combat_lock_ticks} [L] how long flight is off after the player deals damage or takes damage from a living
 * entity or a projectile.
 * <ul>
 * <li>While allowed it keeps {@code mayfly} on and the flying speed set (re-asserted every tick, e.g. after a game
 *     mode reset); flying costs {@link AbsorbCaps#FLIGHT_EXHAUSTION_PER_TICK} exhaustion per tick.</li>
 * <li>Combat lock or a false condition: {@code mayfly} and {@code flying} off, the fall distance restarts at that point
 *     and fall damage then applies normally; flight returns only once the player has landed (mayfly cancels fall
 *     damage, so no escaping the fall by waiting out the lock mid-air).</li>
 * <li>Losing the entry (removal, death, mode OFF, game mode change, logout) restores the previous flying speed and, in
 *     survival / adventure only, clears {@code mayfly} / {@code flying}; creative and spectator abilities are never
 *     touched. A player who could fly and is airborne gets {@link AbsorbCaps#FLIGHT_LOSS_SLOW_FALLING_TICKS} ticks
 *     of slow falling, so a mode toggle is never a death sentence; a combat-locked player does not (no escape from the
 *     fall the lock caused).</li>
 * </ul>
 */
public final class FlightBehavior implements Behavior<FlightBehavior.Params> {
	/** Vanilla's default flying speed, restored when nothing else was recorded. */
	static final float DEFAULT_FLYING_SPEED = 0.05F;

	public record Params(LevelValue speed, LevelValue combatLockTicks, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("speed").forGetter(Params::speed),
				LevelValue.CODEC.optionalFieldOf("combat_lock_ticks", LevelValue.constant(0.0)).forGetter(Params::combatLockTicks),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));

		/** Flying speed at {@code level}: FLIGHT_MIN_SPEED .. FLIGHT_MAX_SPEED (vanilla creative 0.05). */
		public float speedAt(int level) {
			double s = AbilitySupport.at(speed, level);
			return (float) Math.min(AbsorbCaps.FLIGHT_MAX_SPEED, Math.max(AbsorbCaps.FLIGHT_MIN_SPEED, s));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("flight", Params.CODEC, new FlightBehavior());

	@Override
	public int tickInterval(Params params) {
		return 1;
	}

	@Override
	public void onActivate(ActiveBehavior<Params> self, ServerPlayer player) {
		FlightState st = AbilitySupport.state(player, self, FlightState.class, FlightState::new);
		if (!st.owned) {
			st.owned = true;
			st.previousSpeed = player.getAbilities().getFlyingSpeed();
		}
		update(self, player, st);
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		FlightState st = AbilitySupport.peekState(player, self, FlightState.class);
		if (st == null || !st.owned) {
			onActivate(self, player);
			return;
		}
		if (update(self, player, st) && player.getAbilities().flying) player.causeFoodExhaustion(AbsorbCaps.FLIGHT_EXHAUSTION_PER_TICK);
	}

	@Override
	public void onAttacked(ActiveBehavior<Params> self, ServerPlayer player, DamageSource source, float amount) {
		if (amount > 0.0F && (source.getEntity() instanceof LivingEntity || source.getDirectEntity() instanceof Projectile)) lock(self, player);
	}

	@Override
	public void onDealtDamage(ActiveBehavior<Params> self, ServerPlayer player, LivingEntity target, DamageSource source, float amount) {
		if (amount > 0.0F) lock(self, player);
	}

	@Override
	public void onDeactivate(ActiveBehavior<Params> self, ServerPlayer player) {
		FlightState st = AbilitySupport.peekState(player, self, FlightState.class);
		AbilitySupport.clearState(player, self);
		AbilitySupport.clearTimer(player, lockKey(self));
		if (st == null || !st.owned) return;
		Abilities a = player.getAbilities();
		boolean changed = a.getFlyingSpeed() != st.previousSpeed;
		a.setFlyingSpeed(st.previousSpeed);
		if (player.gameMode.getGameModeForPlayer().isSurvival()) {
			boolean couldFly = a.mayfly;
			boolean wasFlying = a.flying;
			changed |= a.mayfly || a.flying;
			a.mayfly = false;
			a.flying = false;
			if (wasFlying) player.resetFallDistance();
			boolean airborne = !player.onGround() && !player.isInWater() && !player.isInLava() && !player.isPassenger();
			if (couldFly && airborne && player.isAlive() && !player.hasDisconnected()) {
				player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, AbsorbCaps.FLIGHT_LOSS_SLOW_FALLING_TICKS));
			}
		}
		if (changed) player.onUpdateAbilities();
	}

	/**
	 * Grants or withholds flight as the lock and condition say; returns true while flight is allowed. Flight taken away
	 * mid-air comes back only after landing (ground, fluid, climbable, riding): mayfly also cancels fall damage, so
	 * re-granting it during the fall would erase the fall the lock caused.
	 */
	private static boolean update(ActiveBehavior<Params> self, ServerPlayer player, FlightState st) {
		if (st.mustLand && landed(player)) st.mustLand = false;
		boolean allowed = !st.mustLand && AbilitySupport.elapsed(player, lockKey(self)) && AbilitySupport.holds(player, self.params().condition(), st);
		Abilities a = player.getAbilities();
		float speed = self.params().speedAt(self.level());
		boolean changed = false;
		if (allowed) {
			if (!a.mayfly || a.getFlyingSpeed() != speed) {
				a.mayfly = true;
				a.setFlyingSpeed(speed);
				changed = true;
			}
		} else if (a.mayfly || a.flying) {
			if (a.flying) player.resetFallDistance();
			a.mayfly = false;
			a.flying = false;
			st.mustLand = !landed(player);
			changed = true;
		}
		if (changed) player.onUpdateAbilities();
		return allowed;
	}

	private static boolean landed(ServerPlayer player) {
		return player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable() || player.isPassenger();
	}

	/** Combat: flight off for combat_lock_ticks (0 = no lock). */
	private static void lock(ActiveBehavior<Params> self, ServerPlayer player) {
		int ticks = AbilitySupport.ticksOf(AbilitySupport.at(self.params().combatLockTicks(), self.level()));
		if (ticks <= 0) return;
		AbilitySupport.setTimer(player, lockKey(self), ticks);
		FlightState st = AbilitySupport.peekState(player, self, FlightState.class);
		if (st != null && st.owned) update(self, player, st);
	}

	/** Timer key of the combat lock (end tick in PlayerRuntime.abilityCooldowns). */
	public static Identifier lockKey(ActiveBehavior<?> self) {
		return AbilitySupport.key(self, "lock");
	}

	/** Whether this entry currently owns the player's mayfly, and the flying speed to restore. */
	static final class FlightState extends AbilitySupport.ConditionCache {
		boolean owned;
		/** Flight was taken away mid-air: withheld until the player lands. */
		boolean mustLand;
		float previousSpeed = DEFAULT_FLYING_SPEED;
	}
}
