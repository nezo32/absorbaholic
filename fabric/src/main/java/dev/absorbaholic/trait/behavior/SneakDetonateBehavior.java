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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * {@code absorbaholic:sneak_detonate} (creeper), trigger {@code sneak_double_tap}: {@code power} [L] (capped at
 * {@link AbsorbCaps#ABILITY_MAX_EXPLOSION_POWER}; &lt;= 0 disables the level), {@code fuse} ticks (plain number),
 * {@code cooldown} [L]. The trigger hisses and starts the fuse (smoke while it burns; a second trigger meanwhile does
 * not fire); when it runs out and the player is still active, {@link AbilitySupport#explode} goes off at the player
 * with a {@code player_explosion} damage source: no fire, no block is damaged (blast resistance is irrelevant), the
 * player is excluded from their own blast, and players they may not harm (PvP off), pets, villagers, armor stands,
 * frames, items and the like take neither damage nor knockback ({@link AbilitySupport#sparedByBlast}); hostile mobs
 * are hit normally. The cooldown starts at detonation. Death, mode OFF or losing the trait defuses it.
 */
public final class SneakDetonateBehavior implements Behavior<SneakDetonateBehavior.Params> {
	public record Params(LevelValue power, int fuse, LevelValue cooldown, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("power").forGetter(Params::power),
				Codec.intRange(0, AbsorbCaps.SNEAK_DETONATE_MAX_FUSE_TICKS).optionalFieldOf("fuse", 30).forGetter(Params::fuse),
				LevelValue.CODEC.fieldOf("cooldown").forGetter(Params::cooldown),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));

		/** Explosion power at {@code level}, capped (0 = inactive). */
		public float powerAt(int level) {
			return (float) Math.min(AbsorbCaps.ABILITY_MAX_EXPLOSION_POWER, Math.max(0.0, AbilitySupport.at(power, level)));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("sneak_detonate", Params.CODEC, new SneakDetonateBehavior());

	@Override
	public int tickInterval(Params params) {
		return 1;
	}

	@Override
	public boolean onSneakDoubleTap(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		if (p.powerAt(self.level()) <= 0.0F || !AbilitySupport.ready(player, self) || isLit(player, self) || !p.condition().test(player)) {
			return false;
		}
		AbilitySupport.setTimer(player, fuseKey(self), p.fuse());
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 1.0F, 0.5F);
		if (p.fuse() == 0) tick(self, player);
		return true;
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		if (!isLit(player, self)) return;
		ServerLevel level = player.level();
		if (!AbilitySupport.elapsed(player, fuseKey(self))) {
			if (AbilitySupport.now(player) % 4 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1.0, player.getZ(), 6, 0.3, 0.5, 0.3, 0.01);
			}
			return;
		}
		AbilitySupport.clearTimer(player, fuseKey(self));
		Params p = self.params();
		float power = p.powerAt(self.level());
		if (power > 0.0F && player.isAlive()) {
			AbilitySupport.explode(level, player, player.damageSources().explosion(player, player), player, player.getX(), player.getY(), player.getZ(), power);
		}
		AbilitySupport.startCooldown(player, self, AbilitySupport.at(p.cooldown(), self.level()));
	}

	@Override
	public void onDeactivate(ActiveBehavior<Params> self, ServerPlayer player) {
		AbilitySupport.clearTimer(player, fuseKey(self));
	}

	/** True while the fuse burns (its end tick is in abilityCooldowns until detonation). */
	private static boolean isLit(ServerPlayer player, ActiveBehavior<Params> self) {
		return AbilitySupport.hasTimer(player, fuseKey(self));
	}

	/** Timer key of the burning fuse (end tick in PlayerRuntime.abilityCooldowns). */
	public static Identifier fuseKey(ActiveBehavior<?> self) {
		return AbilitySupport.key(self, "fuse");
	}
}
