package dev.absorbaholic.trait.behavior;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code absorbaholic:xp_multiplier}: experience from orbs (never commands, enchanting or other direct grants) is
 * multiplied by {@code multiplier} [L] while the shared condition holds. The engine multiplies all instances, clamps the
 * product to {@code AbsorbCaps.EXPERIENCE_FACTOR_MIN..MAX} (0.25..2.5) and rounds stochastically.
 *
 * <pre>{"type": "absorbaholic:xp_multiplier", "multiplier": [1.2, 1.4, 1.6]}</pre>
 */
public final class XpMultiplierBehavior implements Behavior<XpMultiplierBehavior.Params> {
	public record Params(LevelValue multiplier, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("multiplier").validate(v -> CombatBehaviors.atLeast(v, 0.0, "multiplier")).forGetter(Params::multiplier),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("xp_multiplier", Params.CODEC, new XpMultiplierBehavior());

	@Override
	public float experienceFactor(ActiveBehavior<Params> self, ServerPlayer player, int amount) {
		return self.params().condition().test(player) ? (float) self.params().multiplier().at(self.level()) : 1.0F;
	}
}
