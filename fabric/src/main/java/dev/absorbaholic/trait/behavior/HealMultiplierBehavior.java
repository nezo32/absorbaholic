package dev.absorbaholic.trait.behavior;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;

/**
 * {@code absorbaholic:heal_multiplier}: healing is multiplied by {@code multiplier} while the condition holds.
 * {@code source}: {@code "natural"} = only natural regeneration from food ({@code FoodData#tick}), {@code "all"}
 * (default) = every {@code LivingEntity#heal} (potions, regeneration, golden apples, heal_over_time …). The engine
 * clamps the combined factor to 0.25..2.
 *
 * <pre>{"type": "absorbaholic:heal_multiplier", "source": "natural", "multiplier": [0.75, 0.6, 0.45]}</pre>
 */
public final class HealMultiplierBehavior implements Behavior<HealMultiplierBehavior.Params> {
	/** Which heals are affected. */
	public enum HealSource implements StringRepresentable {
		NATURAL("natural"),
		ALL("all");

		public static final Codec<HealSource> CODEC = StringRepresentable.fromEnum(HealSource::values);
		private final String name;

		HealSource(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public record Params(HealSource source, LevelValue multiplier, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				HealSource.CODEC.optionalFieldOf("source", HealSource.ALL).forGetter(Params::source),
				LevelValue.CODEC.fieldOf("multiplier").forGetter(Params::multiplier),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("heal_multiplier", Params.CODEC, new HealMultiplierBehavior());

	@Override
	public float healFactor(ActiveBehavior<Params> self, ServerPlayer player, float amount, boolean natural) {
		Params p = self.params();
		if (p.source() == HealSource.NATURAL && !natural) return 1.0F;
		if (!EntryStates.condition(player, self, p.condition())) return 1.0F;
		return (float) p.multiplier().at(self.level());
	}
}
