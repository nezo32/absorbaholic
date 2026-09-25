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
 * {@code absorbaholic:hunger_drain}: food exhaustion ({@code Player#causeFoodExhaustion}: movement, jumping, attacking,
 * damage, mining) is multiplied by {@code multiplier} while the condition holds. Traits use values &lt; 1, weaknesses
 * values &gt; 1; the engine clamps the combined factor to 0.25..5.
 *
 * <pre>{"type": "absorbaholic:hunger_drain", "condition": "dry", "multiplier": [1.15, 1.3, 1.5]}</pre>
 */
public final class HungerDrainBehavior implements Behavior<HungerDrainBehavior.Params> {
	public record Params(LevelValue multiplier, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("multiplier").forGetter(Params::multiplier),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("hunger_drain", Params.CODEC, new HungerDrainBehavior());

	@Override
	public float exhaustionFactor(ActiveBehavior<Params> self, ServerPlayer player, float amount) {
		return EntryStates.condition(player, self, self.params().condition()) ? (float) self.params().multiplier().at(self.level()) : 1.0F;
	}
}
