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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * {@code absorbaholic:walk_on_fluid}: {@code fluid} ({@code water|lava}), {@code mode}, {@code radius} [L].
 * <ul>
 * <li>{@code frost} (water only, server side): vanilla Frost Walker. Whenever the player's block position changes
 *     while on the ground, not sneaking and not riding, still water sources with air above within {@code radius}
 *     (max {@link AbsorbCaps#FROST_WALK_MAX_RADIUS}; &lt;= 0 disables the level) of the block below the feet turn into
 *     melting {@code frosted_ice} (unabsorbable).</li>
 * <li>{@code solid} (client physics): the fluid's surface is solid ({@link MovementFlags#WALK_ON_WATER} /
 *     {@link MovementFlags#WALK_ON_LAVA} through LivingEntity#canStandOnFluid on both sides); sneaking sinks.
 *     {@code radius} is unused and a condition is not allowed (the flag is static client physics).</li>
 * </ul>
 */
public final class WalkOnFluidBehavior implements Behavior<WalkOnFluidBehavior.Params> {
	/** The fluid walked on. */
	public enum Fluid implements StringRepresentable {
		WATER("water"), LAVA("lava");

		private final String name;

		Fluid(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	/** How the fluid is walked on. */
	public enum Mode implements StringRepresentable {
		FROST("frost"), SOLID("solid");

		private final String name;

		Mode(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public record Params(Fluid fluid, Mode mode, LevelValue radius, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				StringRepresentable.fromEnum(Fluid::values).fieldOf("fluid").forGetter(Params::fluid),
				StringRepresentable.fromEnum(Mode::values).fieldOf("mode").forGetter(Params::mode),
				LevelValue.CODEC.optionalFieldOf("radius", LevelValue.constant(0.0)).forGetter(Params::radius),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			if (mode == Mode.FROST && fluid != Fluid.WATER) return DataResult.error(() -> "mode frost only works on water");
			if (mode == Mode.SOLID && !condition.isAlways()) return DataResult.error(() -> "mode solid is client physics and takes no condition");
			return DataResult.success(this);
		}

		/** Frost radius at {@code level}, capped; 0 = inactive at that level. */
		public int frostRadius(int level) {
			return Math.min(AbsorbCaps.FROST_WALK_MAX_RADIUS, AbilitySupport.ticksOf(AbilitySupport.at(radius, level)));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("walk_on_fluid", Params.CODEC, new WalkOnFluidBehavior());

	@Override
	public int tickInterval(Params params) {
		return params.mode() == Mode.FROST ? 1 : 200;
	}

	@Override
	public MovementState movement(ActiveBehavior<Params> self) {
		if (self.params().mode() != Mode.SOLID) return MovementState.NONE;
		return MovementState.of(self.params().fluid() == Fluid.WATER ? MovementFlags.WALK_ON_WATER : MovementFlags.WALK_ON_LAVA);
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		if (p.mode() != Mode.FROST) return;
		int radius = p.frostRadius(self.level());
		if (radius <= 0 || !player.onGround() || player.isShiftKeyDown() || player.isPassenger()) return;
		FrostState st = AbilitySupport.state(player, self, FrostState.class, FrostState::new);
		BlockPos pos = player.blockPosition();
		if (pos.equals(st.lastPos) || !AbilitySupport.holds(player, p.condition(), st)) return;
		st.lastPos = pos;
		freeze(player, radius);
	}

	@Override
	public void onDeactivate(ActiveBehavior<Params> self, ServerPlayer player) {
		AbilitySupport.clearState(player, self);
	}

	/** Vanilla Frost Walker's ReplaceDisk: still water with air above → frosted ice, disc around the block below the feet. */
	static void freeze(ServerPlayer player, int radius) {
		ServerLevel level = player.level();
		BlockPos center = player.blockPosition().below();
		double x = player.getX();
		double z = player.getZ();
		BlockState ice = Blocks.FROSTED_ICE.defaultBlockState();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, 0, -radius), center.offset(radius, 0, radius))) {
			if (pos.distToCenterSqr(x, pos.getY() + 0.5, z) >= (double) radius * radius) continue;
			if (!level.getBlockState(pos.above()).isAir()) continue;
			if (!level.getBlockState(pos).is(Blocks.WATER) || level.getFluidState(pos).getType() != Fluids.WATER) continue;
			if (!level.isUnobstructed(ice, pos, CollisionContext.empty())) continue;
			if (level.setBlockAndUpdate(pos, ice)) level.gameEvent(player, GameEvent.BLOCK_PLACE, pos);
		}
	}

	/** Last block position the disc was applied at (vanilla applies it on location change). */
	static final class FrostState extends AbilitySupport.ConditionCache {
		@Nullable BlockPos lastPos;
	}
}
