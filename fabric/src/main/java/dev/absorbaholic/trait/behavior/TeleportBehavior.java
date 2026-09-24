package dev.absorbaholic.trait.behavior;

import java.util.Optional;
import java.util.Set;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * {@code absorbaholic:teleport} (enderman, chorus): {@code trigger} ({@code sneak_jump|on_hurt}), {@code mode}
 * ({@code look|random}), {@code range} [L] (server cap {@link AbsorbCaps#TELEPORT_MAX_DISTANCE}), {@code cooldown} [L]
 * (at least {@link AbsorbCaps#ABILITY_MIN_COOLDOWN_TICKS}), {@code chance} [L] (on_hurt only, required there).
 * <ul>
 * <li>{@code look}: raycast {@code range} blocks along the look vector (blocks only, stops at fluids) and take the
 *     farthest safe spot along it (ground up to 3 blocks below the ray).</li>
 * <li>{@code random}: the chorus-fruit search, 16 attempts within ±range horizontally and ±range/2 vertically,
 *     dropping to the ground below each attempt.</li>
 * <li>Safe spot: inside the world border and build height, same dimension, standing on a block with a collision
 *     shape, the standing player box free of blocks and fluids, and no {@code #minecraft:dangerous_for_teleportation}
 *     block at the feet, head or ground (26.2 has no such tag, so its 26.3 contents are also checked by id). Never
 *     farther than the capped range from the start. No spot = no teleport and no cooldown.</li>
 * <li>{@code on_hurt} (weakness): after damage that was not our weakness damage, the void or /kill, roll
 *     {@code chance} (random mode unless look is set); the cooldown still applies.</li>
 * </ul>
 * Teleporting dismounts, resets the fall distance and plays the enderman sound at both ends.
 */
public final class TeleportBehavior implements Behavior<TeleportBehavior.Params> {
	private static final TagKey<Block> DANGEROUS = TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("dangerous_for_teleportation"));
	/** The 26.3 contents of #dangerous_for_teleportation (the tag does not exist on 26.2). */
	private static final Set<Block> DANGEROUS_BLOCKS = Set.of(Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.LAVA_CAULDRON, Blocks.CAMPFIRE,
			Blocks.SOUL_CAMPFIRE, Blocks.CACTUS, Blocks.MAGMA_BLOCK, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.POINTED_DRIPSTONE,
			Blocks.POWDER_SNOW);
	/** Attempts of the random (chorus) search. */
	static final int RANDOM_ATTEMPTS = 16;
	/** Look mode: how far below the ray a landing spot may be. */
	static final int LOOK_MAX_DROP = 3;
	/** Look mode: step when walking back along the ray. */
	private static final double LOOK_STEP = 0.5;

	/** What fires the teleport. */
	public enum Trigger implements StringRepresentable {
		SNEAK_JUMP("sneak_jump"), ON_HURT("on_hurt");

		private final String name;

		Trigger(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	/** How the destination is chosen. */
	public enum Mode implements StringRepresentable {
		LOOK("look"), RANDOM("random");

		private final String name;

		Mode(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public record Params(Trigger trigger, Mode mode, LevelValue range, LevelValue cooldown, Optional<LevelValue> chance, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				StringRepresentable.fromEnum(Trigger::values).fieldOf("trigger").forGetter(Params::trigger),
				StringRepresentable.fromEnum(Mode::values).fieldOf("mode").forGetter(Params::mode),
				LevelValue.CODEC.fieldOf("range").forGetter(Params::range),
				LevelValue.CODEC.fieldOf("cooldown").forGetter(Params::cooldown),
				LevelValue.CODEC.optionalFieldOf("chance").forGetter(Params::chance),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			if (trigger == Trigger.ON_HURT && chance.isEmpty()) return DataResult.error(() -> "trigger on_hurt needs \"chance\"");
			if (trigger == Trigger.SNEAK_JUMP && chance.isPresent()) return DataResult.error(() -> "\"chance\" is only used by trigger on_hurt");
			return DataResult.success(this);
		}

		/** Teleport range at {@code level}, capped at TELEPORT_MAX_DISTANCE (&lt;= 0 = inactive). */
		public double rangeAt(int level) {
			return Math.min(AbsorbCaps.TELEPORT_MAX_DISTANCE, AbilitySupport.at(range, level));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("teleport", Params.CODEC, new TeleportBehavior());

	@Override
	public boolean onSneakJump(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		if (p.trigger() != Trigger.SNEAK_JUMP || !AbilitySupport.ready(player, self) || !p.condition().test(player)) return false;
		return teleport(self, player);
	}

	@Override
	public void onAttacked(ActiveBehavior<Params> self, ServerPlayer player, DamageSource source, float amount) {
		Params p = self.params();
		if (p.trigger() != Trigger.ON_HURT || !(amount > 0.0F) || !player.isAlive()) return;
		if (source.is(WeaknessDamage.TYPE) || source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.GENERIC_KILL)
				|| source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return;
		}
		if (!AbilitySupport.ready(player, self) || !p.condition().test(player)) return;
		double chance = p.chance().map(c -> AbilitySupport.at(c, self.level())).orElse(0.0);
		if (!(chance > 0.0) || player.getRandom().nextDouble() >= chance) return;
		teleport(self, player);
	}

	/** Finds a spot and teleports there; starts the cooldown on success. */
	private static boolean teleport(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		double range = p.rangeAt(self.level());
		if (range < 1.0) return false;
		Vec3 dest = p.mode() == Mode.LOOK ? lookTarget(player, range) : randomTarget(player, range);
		if (dest == null) return false;
		moveTo(player, dest);
		AbilitySupport.startCooldown(player, self, AbilitySupport.at(p.cooldown(), self.level()));
		return true;
	}

	/** The farthest safe spot along the look ray (blocks only; the ray stops at fluids). */
	static @Nullable Vec3 lookTarget(ServerPlayer player, double range) {
		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();
		Vec3 dir = player.getViewVector(1.0F);
		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
		double reach = hit.getType() == HitResult.Type.MISS ? range : hit.getLocation().distanceTo(eye);
		BlockPos start = player.blockPosition();
		for (double d = reach - 0.3; d >= 1.0; d -= LOOK_STEP) {
			BlockPos at = BlockPos.containing(eye.add(dir.scale(d)));
			for (int drop = 0; drop <= LOOK_MAX_DROP; drop++) {
				BlockPos pos = at.below(drop);
				if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
					BlockPos feet = pos.above();
					Vec3 dest = safeSpot(player, feet.getX() + 0.5, feet, feet.getZ() + 0.5, range);
					if (dest != null && !feet.equals(start)) return dest;
					break;
				}
				if (!level.getFluidState(pos).isEmpty()) break;
			}
		}
		return null;
	}

	/** Chorus fruit: up to 16 random attempts in ±range (±range/2 vertically), each dropping to the ground below it. */
	static @Nullable Vec3 randomTarget(ServerPlayer player, double range) {
		ServerLevel level = player.level();
		RandomSource random = player.getRandom();
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
			double x = player.getX() + (random.nextDouble() - 0.5) * 2.0 * range;
			double y = Mth.clamp(player.getY() + (random.nextDouble() - 0.5) * range, minY, maxY);
			double z = player.getZ() + (random.nextDouble() - 0.5) * 2.0 * range;
			BlockPos pos = BlockPos.containing(x, y, z);
			if (!level.hasChunkAt(pos)) continue;
			double lowest = player.getY() - range;
			while (pos.getY() > minY && pos.getY() > lowest && level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) {
				pos = pos.below();
			}
			Vec3 dest = safeSpot(player, x, pos, z, range);
			if (dest != null) return dest;
		}
		return null;
	}

	/**
	 * The standing position on the ground below {@code feet} at (x, z), or null if it is not safe (see class doc) or
	 * farther than {@code range} from the player.
	 */
	static @Nullable Vec3 safeSpot(ServerPlayer player, double x, BlockPos feet, double z, double range) {
		ServerLevel level = player.level();
		if (!level.isInsideBuildHeight(feet.getY()) || !level.getWorldBorder().isWithinBounds(feet)) return null;
		BlockPos groundPos = feet.below();
		BlockState ground = level.getBlockState(groundPos);
		VoxelShape shape = ground.getCollisionShape(level, groundPos);
		if (shape.isEmpty() || dangerous(ground) || dangerous(level.getBlockState(feet)) || dangerous(level.getBlockState(feet.above()))) return null;
		double top = shape.max(Direction.Axis.Y);
		if (!(top > 0.0) || top > 1.5) return null;
		Vec3 dest = new Vec3(x, groundPos.getY() + top, z);
		if (dest.distanceTo(player.position()) > range) return null;
		AABB box = player.getDimensions(Pose.STANDING).makeBoundingBox(dest);
		if (!level.getWorldBorder().isWithinBounds(box) || !level.noCollision(player, box) || level.containsAnyLiquid(box)) return null;
		return dest;
	}

	private static boolean dangerous(BlockState state) {
		return state.is(DANGEROUS) || DANGEROUS_BLOCKS.contains(state.getBlock());
	}

	/** Teleports within the level: dismount, move, reset the fall, sound and particles at both ends. */
	static void moveTo(ServerPlayer player, Vec3 dest) {
		ServerLevel level = player.level();
		Vec3 from = player.position();
		if (player.isPassenger()) player.stopRiding();
		level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
		player.teleportTo(dest.x, dest.y, dest.z);
		player.resetFallDistance();
		level.gameEvent(GameEvent.TELEPORT, from, GameEvent.Context.of(player));
		level.broadcastEntityEvent(player, (byte) 46);
		level.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
	}
}
