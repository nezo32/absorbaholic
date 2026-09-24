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
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jspecify.annotations.Nullable;

/**
 * {@code absorbaholic:detection_range}: the distance at which matching mobs ({@code entities}, default every
 * {@code Enemy}) notice the player is multiplied by {@code multiplier} while the condition holds.
 * <ul>
 * <li>Every entry multiplies the visibility vanilla targeting uses ({@code getVisibilityPercent}, like a mob head);
 *     the engine combines all entries and clamps to 0.25..3. This is how values &lt; 1 hide the player.</li>
 * <li>Vanilla never looks beyond a mob's {@code FOLLOW_RANGE}, so while this entry's multiplier is &gt; 1, every 20
 *     ticks matching mobs that hunt players on their own, have no target and see the player acquire it when it is
 *     within {@code FOLLOW_RANGE × combined factor} (at most 48 blocks). Neutral mobs (endermen, zombified piglins …),
 *     piglins calmed by gold and the Warden are never provoked. Mobs provoked this way calm down when the entry goes
 *     away (unless the player hurt them).</li>
 * </ul>
 *
 * <pre>{"type": "absorbaholic:detection_range", "entities": ["#minecraft:skeletons"], "multiplier": [1.3, 1.6, 2.0]}</pre>
 */
public final class DetectionRangeBehavior implements Behavior<DetectionRangeBehavior.Params> {
	static final int SCAN_INTERVAL = 20;

	public record Params(TargetFilter entities, LevelValue multiplier, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				TargetFilter.CODEC.optionalFieldOf("entities", TargetFilter.HOSTILE).forGetter(Params::entities),
				LevelValue.CODEC.fieldOf("multiplier").forGetter(Params::multiplier),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("detection_range", Params.CODEC, new DetectionRangeBehavior());

	@Override
	public double visibilityFactor(ActiveBehavior<Params> self, ServerPlayer player, @Nullable Entity looker) {
		Params p = self.params();
		if (looker == null || !p.entities().test(looker) || !EntryStates.condition(player, self, p.condition())) return 1.0;
		double m = p.multiplier().at(self.level());
		return m > 0.0 ? m : 1.0;
	}

	@Override
	public int tickInterval(Params p) {
		return SCAN_INTERVAL;
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		EntryStates.State state = EntryStates.get(player, self);
		MobRules.prune(state.provoked, player);
		if (!(p.multiplier().at(self.level()) > 1.0) || player.isInvisible() || !EntryStates.condition(player, self, p.condition())) return;
		for (Mob mob : MobRules.scan(player, Mob.class, AbsorbCaps.DETECTION_MAX_RADIUS,
				m -> p.entities().test(m) && MobRules.affectable(m, player, p.entities()) && MobRules.huntsPlayers(m, player))) {
			if (mob.getTarget() != null) continue;
			AttributeInstance follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
			if (follow == null) continue;
			double factor = TraitEngine.visibilityFactor(player, mob); // every entry combined, clamped by the engine
			if (!(factor > 1.0)) continue;
			double range = Math.min(follow.getValue() * factor, AbsorbCaps.DETECTION_MAX_RADIUS);
			if (mob.distanceToSqr(player) > range * range || !mob.hasLineOfSight(player)) continue;
			if (MobRules.provoke(mob, player)) state.provoked.add(mob);
		}
	}

	@Override
	public void onDeactivate(ActiveBehavior<Params> self, ServerPlayer player) {
		EntryStates.State state = EntryStates.peek(player, self);
		if (state != null) MobRules.calmAll(state.provoked, player);
		EntryStates.remove(player, self);
	}
}
