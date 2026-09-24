package dev.absorbaholic.trait.behavior;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.Hook;
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * {@code absorbaholic:air_jump} (bee), trigger {@code air_jump}: {@code charges} [L] jumps per airtime,
 * {@code velocity} [L] upward (capped at {@link AbsorbCaps#ABILITY_MAX_VELOCITY}), {@code cooldown} ticks between
 * bursts (a plain number, not level-scaled). A burst sets {@code vy = max(vy, velocity)} on the movement the client
 * last reported, resets the fall distance, and sends the motion (server authoritative, so vanilla clients follow).
 * Charges refill on the ground, in water or lava, on climbable blocks and when riding. {@code glide} takes over only
 * when no air-jump charges are left ({@link #chargesLeft}). The engine charges AIR_JUMP_EXHAUSTION per burst.
 */
public final class AirJumpBehavior implements Behavior<AirJumpBehavior.Params> {
	public record Params(LevelValue charges, LevelValue velocity, int cooldown, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("charges").forGetter(Params::charges),
				LevelValue.CODEC.fieldOf("velocity").forGetter(Params::velocity),
				Codec.intRange(0, 1200).optionalFieldOf("cooldown", 0).forGetter(Params::cooldown),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));

		/** Charges per airtime at {@code level} (0 = inactive). */
		public int chargesAt(int level) {
			double c = AbilitySupport.at(charges, level);
			return c > 0.0 ? (int) Math.min(64, Math.floor(c)) : 0;
		}

		/** Burst velocity at {@code level}, capped. */
		public double velocityAt(int level) {
			return Math.min(AbsorbCaps.ABILITY_MAX_VELOCITY, Math.max(0.0, AbilitySupport.at(velocity, level)));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("air_jump", Params.CODEC, new AirJumpBehavior());

	@Override
	public int tickInterval(Params params) {
		return 1;
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		ChargeState st = AbilitySupport.peekState(player, self, ChargeState.class);
		if (st != null && st.used > 0 && refills(player)) st.used = 0;
	}

	@Override
	public boolean onAirJump(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		int charges = p.chargesAt(self.level());
		if (charges <= 0 || !AbilitySupport.ready(player, self) || !p.condition().test(player)) return false;
		ChargeState st = AbilitySupport.state(player, self, ChargeState.class, ChargeState::new);
		if (refills(player)) st.used = 0;
		if (st.used >= charges) return false;
		st.used++;
		AbilitySupport.setTimer(player, AbilitySupport.key(self), p.cooldown());

		Vec3 v = player.getKnownMovement();
		player.setDeltaMovement(v.x, Math.max(v.y, p.velocityAt(self.level())), v.z);
		AbilitySupport.syncMotion(player);
		player.resetFallDistance();
		player.level().sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(), 8, 0.3, 0.05, 0.3, 0.02);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BAT_TAKEOFF, SoundSource.PLAYERS, 0.6F, 1.4F);
		return true;
	}

	@Override
	public void onDeactivate(ActiveBehavior<Params> self, ServerPlayer player) {
		AbilitySupport.clearState(player, self);
	}

	/** True if any active air_jump entry of the player could still burst this airtime (charges and condition). */
	public static boolean chargesLeft(ServerPlayer player) {
		for (ActiveBehavior<?> a : TraitEngine.active(player).forHook(Hook.AIR_JUMP)) {
			if (a.type() != TYPE) continue;
			@SuppressWarnings("unchecked")
			ActiveBehavior<Params> entry = (ActiveBehavior<Params>) a;
			int charges = entry.params().chargesAt(entry.level());
			if (charges <= 0 || !entry.params().condition().test(player)) continue;
			ChargeState st = AbilitySupport.peekState(player, entry, ChargeState.class);
			if (st == null || refills(player) || st.used < charges) return true;
		}
		return false;
	}

	/** Touching ground, fluid or a climbable, or riding: a new airtime. */
	static boolean refills(ServerPlayer player) {
		return player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable() || player.isPassenger();
	}

	/** Charges spent in the current airtime. */
	static final class ChargeState {
		int used;
	}
}
