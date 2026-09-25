package dev.absorbaholic.trait.behavior;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.net.MovementPayload;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.MovementFlags;
import dev.absorbaholic.trait.MovementState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * {@code absorbaholic:sink_in_water} (iron, iron golem, masonry weaknesses): {@code speed} [L] blocks/tick². While in
 * water, not riding, not flying and not in a bubble column, the player sinks: {@code vy = max(vy - speed,
 * SINK_MAX_FALL_VELOCITY)} and jumping does not swim up (except climbing out at a shore).
 * <ul>
 * <li>Modded clients simulate it themselves ({@link MovementFlags#SINK_IN_WATER} + speed in {@link MovementState},
 *     LivingEntityMixin travel tweak), so the server leaves their motion alone.</li>
 * <li>Vanilla clients: every 2 ticks the server applies two ticks' worth to the last movement the client reported and
 *     sends it. This jitters slightly, as the catalog accepts.</li>
 * </ul>
 */
public final class SinkInWaterBehavior implements Behavior<SinkInWaterBehavior.Params> {
	/** Server push interval for vanilla clients. */
	static final int PUSH_INTERVAL = 2;

	public record Params(LevelValue speed, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("speed").forGetter(Params::speed),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));

		/** Sink acceleration at {@code level} (&gt;= 0). */
		public float speedAt(int level) {
			return (float) Math.max(0.0, AbilitySupport.at(speed, level));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("sink_in_water", Params.CODEC, new SinkInWaterBehavior());

	@Override
	public int tickInterval(Params params) {
		return PUSH_INTERVAL;
	}

	@Override
	public MovementState movement(ActiveBehavior<Params> self) {
		// a conditional sink cannot be predicted by the client: the server path handles it for everyone
		if (!self.params().condition().isAlways()) return MovementState.NONE;
		return new MovementState(MovementFlags.SINK_IN_WATER, 0.0F, self.params().speedAt(self.level()));
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		float speed = p.speedAt(self.level());
		if (speed <= 0.0F || !player.isInWater() || player.isPassenger() || player.getAbilities().flying
				|| player.getInBlockState().is(Blocks.BUBBLE_COLUMN)) {
			return;
		}
		if (p.condition().isAlways() && ServerPlayNetworking.canSend(player, MovementPayload.TYPE)) return;
		if (!p.condition().isAlways() && !AbilitySupport.holds(player, p.condition(),
				AbilitySupport.state(player, self, AbilitySupport.ConditionCache.class, AbilitySupport.ConditionCache::new))) {
			return;
		}
		Vec3 v = player.getKnownMovement();
		double vy = sink(v.y, speed * PUSH_INTERVAL, player.horizontalCollision);
		if (vy == v.y) return;
		player.setDeltaMovement(v.x, vy, v.z);
		AbilitySupport.syncMotion(player);
	}

	@Override
	public void onDeactivate(ActiveBehavior<Params> self, ServerPlayer player) {
		AbilitySupport.clearState(player, self);
	}

	/** The sink formula: no upward swimming (unless climbing out at a wall), then accelerate down to the terminal speed. */
	public static double sink(double vy, double speed, boolean againstWall) {
		double y = againstWall ? vy : Math.min(vy, 0.0);
		if (y > AbsorbCaps.SINK_MAX_FALL_VELOCITY) y = Math.max(y - speed, AbsorbCaps.SINK_MAX_FALL_VELOCITY);
		return y;
	}
}
