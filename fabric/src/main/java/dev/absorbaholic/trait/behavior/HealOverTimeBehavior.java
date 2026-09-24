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
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code absorbaholic:heal_over_time}: every {@code interval} ticks (default 20), while the condition holds and the
 * player is hurt and alive, heals {@code amount} HP through {@code LivingEntity#heal} (so {@code heal_multiplier}
 * "all" applies). An amount &lt;= 0 disables the entry at that level.
 *
 * <pre>{"type": "absorbaholic:heal_over_time", "condition": "low_health", "amount": [1.0, 1.5, 2.0], "interval": 40}</pre>
 */
public final class HealOverTimeBehavior implements Behavior<HealOverTimeBehavior.Params> {
	public record Params(LevelValue amount, int interval, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("amount").forGetter(Params::amount),
				Codec.intRange(AbsorbCaps.BEHAVIOR_MIN_TICK_INTERVAL, 72000).optionalFieldOf("interval", 20).forGetter(Params::interval),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("heal_over_time", Params.CODEC, new HealOverTimeBehavior());

	@Override
	public int tickInterval(Params p) {
		return p.interval();
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		float amount = (float) self.params().amount().at(self.level());
		if (!(amount > 0.0F) || !player.isAlive() || player.getHealth() >= player.getMaxHealth()) return;
		if (EntryStates.condition(player, self, self.params().condition())) player.heal(amount);
	}
}
