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
import net.minecraft.world.item.ItemStack;

/**
 * {@code absorbaholic:durability_multiplier}: durability damage to any item the player uses or wears (after
 * Unbreaking) is multiplied by {@code multiplier} [L] while the shared condition holds. The engine multiplies all
 * instances, clamps the product to {@code AbsorbCaps.DURABILITY_FACTOR_MIN..MAX} (0.5..3) and rounds stochastically.
 *
 * <pre>{"type": "absorbaholic:durability_multiplier", "multiplier": [1.25, 1.5, 2.0]}</pre>
 */
public final class DurabilityMultiplierBehavior implements Behavior<DurabilityMultiplierBehavior.Params> {
	public record Params(LevelValue multiplier, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("multiplier").validate(v -> CombatBehaviors.atLeast(v, 0.0, "multiplier")).forGetter(Params::multiplier),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));
	}

	public static final BehaviorType<Params> TYPE =
			BehaviorRegistry.register("durability_multiplier", Params.CODEC, new DurabilityMultiplierBehavior());

	@Override
	public float durabilityFactor(ActiveBehavior<Params> self, ServerPlayer player, ItemStack stack, int amount) {
		return self.params().condition().test(player) ? (float) self.params().multiplier().at(self.level()) : 1.0F;
	}
}
