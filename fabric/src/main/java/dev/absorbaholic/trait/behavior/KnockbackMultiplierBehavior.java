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
import dev.absorbaholic.trait.TargetFilter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.jspecify.annotations.Nullable;

/**
 * {@code absorbaholic:knockback_multiplier}: the strength of knockback the player takes ({@code LivingEntity#knockback}:
 * melee hits, projectiles, shields, sonic booms …; not explosions, which have their own attribute) is multiplied by
 * {@code multiplier} [L] while the condition holds; {@code attacker} (optional) limits it to knockback caused by
 * matching entities. Vanilla knockback resistance still applies after it. Weaknesses use values &gt; 1, traits values
 * &lt; 1; the engine clamps the combined factor to {@code AbsorbCaps.KNOCKBACK_FACTOR_MIN..MAX} (0.5..3). Unlike a
 * negative {@code knockback_resistance} (vanilla clamps the attribute at 0, so it does nothing), this always works.
 *
 * <pre>{"type": "absorbaholic:knockback_multiplier", "multiplier": [1.5, 1.75, 2.0]}</pre>
 */
public final class KnockbackMultiplierBehavior implements Behavior<KnockbackMultiplierBehavior.Params> {
	public record Params(LevelValue multiplier, TargetFilter attacker, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("multiplier")
						.validate(v -> CombatBehaviors.atLeast(v, AbsorbCaps.KNOCKBACK_FACTOR_MIN, "multiplier"))
						.forGetter(Params::multiplier),
				TargetFilter.CODEC.optionalFieldOf("attacker", TargetFilter.ANY).forGetter(Params::attacker),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));

		boolean matches(ServerPlayer player, @Nullable DamageSource source) {
			if (!attacker.isAny() && (source == null || source.getEntity() == null || !attacker.test(source.getEntity()))) return false;
			return condition.test(player);
		}
	}

	public static final BehaviorType<Params> TYPE =
			BehaviorRegistry.register("knockback_multiplier", Params.CODEC, new KnockbackMultiplierBehavior());

	@Override
	public float knockbackFactor(ActiveBehavior<Params> self, ServerPlayer player, @Nullable DamageSource source, double strength) {
		return self.params().matches(player, source) ? (float) self.params().multiplier().at(self.level()) : 1.0F;
	}
}
