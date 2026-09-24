package dev.absorbaholic.trait.behavior;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.WeaknessDamage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * {@code absorbaholic:environment_damage}: every {@code interval} ticks (default 20) while the condition holds, either
 * {@code damage} HP of {@code absorbaholic:weakness} damage through the weakness damage gate ({@link WeaknessDamage}:
 * no armor, no knockback, at most 8 HP per 20 ticks), or ignition for {@code ignite_seconds} (fire ticks are raised to
 * at least that; the burn is vanilla fire damage, so fire resistance and fire immunity counter it). With
 * {@code helmet_blocks}, any head item blocks the effect like an undead mob's helmet and loses 1 durability instead.
 * A level value &lt;= 0 disables the entry at that level.
 *
 * <pre>{"type": "absorbaholic:environment_damage", "condition": "in_sunlight", "ignite_seconds": [3, 5, 8], "helmet_blocks": true}</pre>
 */
public final class EnvironmentDamageBehavior implements Behavior<EnvironmentDamageBehavior.Params> {
	public record Params(Optional<LevelValue> damage, Optional<LevelValue> igniteSeconds, int interval, boolean helmetBlocks, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				LevelValue.CODEC.optionalFieldOf("damage").forGetter(Params::damage),
				LevelValue.CODEC.optionalFieldOf("ignite_seconds").forGetter(Params::igniteSeconds),
				Codec.intRange(AbsorbCaps.BEHAVIOR_MIN_TICK_INTERVAL, 72000).optionalFieldOf("interval", 20).forGetter(Params::interval),
				Codec.BOOL.optionalFieldOf("helmet_blocks", false).forGetter(Params::helmetBlocks),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			if (damage.isPresent() == igniteSeconds.isPresent()) return DataResult.error(() -> "needs exactly one of \"damage\" or \"ignite_seconds\"");
			return DataResult.success(this);
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("environment_damage", Params.CODEC, new EnvironmentDamageBehavior());

	@Override
	public int tickInterval(Params p) {
		return p.interval();
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		double damage = p.damage().map(v -> v.at(self.level())).orElse(0.0);
		double seconds = p.igniteSeconds().map(v -> v.at(self.level())).orElse(0.0);
		if (!(damage > 0.0) && !(seconds > 0.0)) return;
		if (!EntryStates.condition(player, self, p.condition())) return;
		if (p.helmetBlocks()) {
			ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
			if (!helmet.isEmpty()) {
				if (helmet.isDamageableItem()) helmet.hurtAndBreak(1, player, EquipmentSlot.HEAD);
				return;
			}
		}
		if (damage > 0.0) {
			WeaknessDamage.hurt(player, (float) damage);
		} else {
			int ticks = (int) Math.round(seconds * 20.0);
			if (ticks > player.getRemainingFireTicks()) player.setRemainingFireTicks(ticks);
		}
	}
}
