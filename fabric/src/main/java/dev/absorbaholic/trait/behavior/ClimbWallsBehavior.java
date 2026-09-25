package dev.absorbaholic.trait.behavior;

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
import dev.absorbaholic.trait.MovementFlags;
import dev.absorbaholic.trait.MovementState;

/**
 * {@code absorbaholic:climb_walls} (spider): {@code speed} [L] upward blocks per tick. Grants
 * {@link MovementFlags#CLIMB_WALLS}: while pushing against a wall the player is on a "ladder" (LivingEntity#onClimbable
 * on both sides, so sneaking holds position and climbing resets fall distance) and climbs at {@code speed} (capped at
 * {@link AbsorbCaps#ABILITY_MAX_VELOCITY}) instead of the ladder's 0.2. Client physics: inert on vanilla clients, and a
 * condition is not allowed.
 */
public final class ClimbWallsBehavior implements Behavior<ClimbWallsBehavior.Params> {
	public record Params(LevelValue speed, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("speed").forGetter(Params::speed),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(p -> p.condition().isAlways()
				? DataResult.success(p)
				: DataResult.error(() -> "climb_walls is client physics and takes no condition"));

		/** Climb speed at {@code level}: 0 (vanilla ladder speed) .. ABILITY_MAX_VELOCITY. */
		public float speedAt(int level) {
			return (float) Math.min(AbsorbCaps.ABILITY_MAX_VELOCITY, Math.max(0.0, AbilitySupport.at(speed, level)));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("climb_walls", Params.CODEC, new ClimbWallsBehavior());

	@Override
	public MovementState movement(ActiveBehavior<Params> self) {
		return new MovementState(MovementFlags.CLIMB_WALLS, self.params().speedAt(self.level()), 0.0F);
	}
}
