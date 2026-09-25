package dev.absorbaholic.trait.behavior;

import java.util.Comparator;
import java.util.Optional;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AmethystBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * {@code absorbaholic:shoot_projectile} (blaze, breeze, ender dragon, evoker, ghast, llama, shulker, snow golem),
 * trigger {@code sneak_swing}: {@code projectile}, {@code count} [L], {@code cooldown} [L], optional {@code damage} [L]
 * (snowball, llama_spit, evoker_fangs, arrow) and {@code explosion_power} [L] (fireball).
 * <ul>
 * <li>Projectiles leave the eye along the look vector, owned by the player (they count as the player's attacks);
 *     {@code count > 1} fans them over {@value #SPREAD_DEGREES}° in one tick. {@code count} is capped at
 *     {@link AbsorbCaps#ABILITY_MAX_TARGETS} ({@link AbsorbCaps#EVOKER_FANGS_MAX} fangs); &lt;= 0 disables the level.</li>
 * <li>{@code fireball}: power capped at {@link AbsorbCaps#FIREBALL_MAX_EXPLOSION_POWER}; the blast is
 *     {@link AbilitySupport#explode}: no blocks, no fire, and players the shooter may not harm (PvP off), pets,
 *     villagers, armor stands, frames, items and the like take neither damage nor knockback.
 *     {@code small_fireball} sets the entity it hits on fire but never places fire blocks.</li>
 * <li>The fire / trait-only projectiles (small_fireball, fireball, dragon_fireball, wither_skull) never change a block:
 *     of the vanilla block reactions to a projectile they keep only the harmless ones (target, bell, amethyst,
 *     big dripleaf): no TNT priming, candle or campfire lighting, pot or chorus breaking.</li>
 * <li>{@code snowball} / {@code llama_spit}: {@code damage} replaces vanilla's 0 / 1. {@code evoker_fangs}: a line
 *     on the ground along the look direction, one block apart; {@code damage} rescales vanilla's 6 through the
 *     outgoing damage factor (so 3..12 is allowed). {@code arrow}: base damage, never picked up.</li>
 * <li>{@code shulker_bullet}: homes on the nearest {@code Enemy} within {@value AbsorbCaps#SHULKER_BULLET_RANGE} blocks in a
 *     {@value #SHULKER_CONE_DEGREES}° cone with line of sight; none = no shot and no cooldown.</li>
 * <li>{@code dragon_fireball}: its lingering breath never hurts the player who fired it. {@code wither_skull}
 *     (reserved, unused by v1 data): vanilla hit damage, but its blast is {@link AbilitySupport#explode} (never
 *     breaks blocks, whatever mobGriefing says). {@code arrow} (reserved) behaves as vanilla.</li>
 * </ul>
 * Our damage-tuned or block-safe subclasses are never saved with the chunk (they are discarded on unload), so they
 * can never come back as vanilla projectiles.
 */
public final class ShootProjectileBehavior implements Behavior<ShootProjectileBehavior.Params> {
	/** Total fan angle of a multi-shot. */
	static final float SPREAD_DEGREES = 10.0F;
	/** Shulker bullet target cone (range: {@link AbsorbCaps#SHULKER_BULLET_RANGE}). */
	static final double SHULKER_CONE_DEGREES = 30.0;

	/** Supported projectiles. */
	public enum Kind implements StringRepresentable {
		SMALL_FIREBALL("small_fireball", SoundEvents.BLAZE_SHOOT),
		FIREBALL("fireball", SoundEvents.GHAST_SHOOT),
		WIND_CHARGE("wind_charge", SoundEvents.WIND_CHARGE_THROW),
		SNOWBALL("snowball", SoundEvents.SNOWBALL_THROW),
		LLAMA_SPIT("llama_spit", SoundEvents.LLAMA_SPIT),
		SHULKER_BULLET("shulker_bullet", SoundEvents.SHULKER_SHOOT),
		DRAGON_FIREBALL("dragon_fireball", SoundEvents.ENDER_DRAGON_SHOOT),
		WITHER_SKULL("wither_skull", SoundEvents.WITHER_SHOOT),
		EVOKER_FANGS("evoker_fangs", SoundEvents.EVOKER_CAST_SPELL),
		ARROW("arrow", SoundEvents.ARROW_SHOOT);

		private final String name;
		final SoundEvent sound;

		Kind(String name, SoundEvent sound) {
			this.name = name;
			this.sound = sound;
		}

		@Override
		public String getSerializedName() {
			return name;
		}

		boolean usesDamage() {
			return this == SNOWBALL || this == LLAMA_SPIT || this == EVOKER_FANGS || this == ARROW;
		}

		int maxCount() {
			return this == EVOKER_FANGS ? AbsorbCaps.EVOKER_FANGS_MAX : AbsorbCaps.ABILITY_MAX_TARGETS;
		}
	}

	public record Params(Kind projectile, LevelValue count, LevelValue cooldown, Optional<LevelValue> damage,
			Optional<LevelValue> explosionPower, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				StringRepresentable.fromEnum(Kind::values).fieldOf("projectile").forGetter(Params::projectile),
				LevelValue.CODEC.optionalFieldOf("count", LevelValue.constant(1.0)).forGetter(Params::count),
				LevelValue.CODEC.fieldOf("cooldown").forGetter(Params::cooldown),
				LevelValue.CODEC.optionalFieldOf("damage").forGetter(Params::damage),
				LevelValue.CODEC.optionalFieldOf("explosion_power").forGetter(Params::explosionPower),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			if (damage.isPresent() && !projectile.usesDamage()) {
				return DataResult.error(() -> "\"damage\" is not used by projectile " + projectile.getSerializedName());
			}
			if (explosionPower.isPresent() && projectile != Kind.FIREBALL) {
				return DataResult.error(() -> "\"explosion_power\" is only used by projectile fireball");
			}
			if (projectile == Kind.EVOKER_FANGS && damage.isPresent()) {
				double min = AbsorbCaps.EVOKER_FANG_BASE_DAMAGE * AbsorbCaps.DAMAGE_DEALT_MIN;
				double max = AbsorbCaps.EVOKER_FANG_BASE_DAMAGE * AbsorbCaps.DAMAGE_DEALT_MAX;
				for (int level = 1; level <= AbsorbCaps.MAX_MAX_LEVEL; level++) {
					double d = damage.get().at(level);
					if (d < min || d > max) {
						return DataResult.error(() -> "evoker_fangs \"damage\" must be within " + min + ".." + max);
					}
				}
			}
			return DataResult.success(this);
		}

		/** Projectiles per shot at {@code level}, capped (0 = inactive). */
		public int countAt(int level) {
			double c = AbilitySupport.at(count, level);
			return c > 0.0 ? (int) Math.min(projectile.maxCount(), Math.floor(c)) : 0;
		}

		/** {@code damage} at {@code level}, or -1 when absent (vanilla damage). */
		public float damageAt(int level) {
			return damage.map(d -> (float) Math.max(0.0, AbilitySupport.at(d, level))).orElse(-1.0F);
		}

		/** Fireball explosion power at {@code level}: default 1, capped. */
		public float explosionPowerAt(int level) {
			double p = explosionPower.map(e -> AbilitySupport.at(e, level)).orElse(1.0);
			return (float) Math.min(AbsorbCaps.FIREBALL_MAX_EXPLOSION_POWER, Math.max(0.0, p));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("shoot_projectile", Params.CODEC, new ShootProjectileBehavior());

	@Override
	public boolean onSneakSwing(ActiveBehavior<Params> self, ServerPlayer player, @Nullable Entity crosshair) {
		Params p = self.params();
		int count = p.countAt(self.level());
		if (count <= 0 || !AbilitySupport.ready(player, self) || !p.condition().test(player)) return false;
		if (!shoot(self, player, count, crosshair)) return false;
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), p.projectile().sound, SoundSource.PLAYERS, 1.0F,
				0.9F + player.getRandom().nextFloat() * 0.2F);
		AbilitySupport.startCooldown(player, self, AbilitySupport.at(p.cooldown(), self.level()));
		return true;
	}

	/** The player's own dragon breath never hurts them. */
	@Override
	public boolean isImmuneTo(ActiveBehavior<Params> self, ServerPlayer player, DamageSource source) {
		return self.params().projectile() == Kind.DRAGON_FIREBALL && source.getDirectEntity() instanceof AreaEffectCloud && source.getEntity() == player;
	}

	/** Evoker fangs: {@code damage} rescales vanilla's fixed 6 for fangs this player summoned. */
	@Override
	public float outgoingDamageFactor(ActiveBehavior<Params> self, ServerPlayer player, LivingEntity target, DamageSource source, float amount) {
		Params p = self.params();
		if (p.projectile() != Kind.EVOKER_FANGS || p.damage().isEmpty()) return 1.0F;
		if (!(source.getDirectEntity() instanceof EvokerFangs fangs) || fangs.getOwner() != player) return 1.0F;
		return p.damageAt(self.level()) / AbsorbCaps.EVOKER_FANG_BASE_DAMAGE;
	}

	/** Spawns the shot; false if nothing could be fired (no shulker target, no ground for fangs). */
	private static boolean shoot(ActiveBehavior<Params> self, ServerPlayer player, int count, @Nullable Entity crosshair) {
		Params p = self.params();
		ServerLevel level = player.level();
		Vec3 look = player.getViewVector(1.0F);
		switch (p.projectile()) {
			case SHULKER_BULLET -> {
				LivingEntity target = shulkerTarget(player, crosshair);
				if (target == null) return false;
				for (int i = 0; i < count; i++) level.addFreshEntity(new ShulkerBullet(level, player, target, Direction.Axis.Y));
				return true;
			}
			case EVOKER_FANGS -> {
				return fangs(player, look, count);
			}
			default -> {
				int level1 = self.level();
				for (int i = 0; i < count; i++) {
					float yaw = count == 1 ? 0.0F : -SPREAD_DEGREES / 2.0F + SPREAD_DEGREES * i / (count - 1);
					Vec3 dir = look.yRot(yaw * Mth.DEG_TO_RAD).normalize();
					level.addFreshEntity(create(p, level1, player, dir));
				}
				return true;
			}
		}
	}

	/** One projectile of the flying kinds, placed just in front of the eye. */
	private static Projectile create(Params p, int level, ServerPlayer player, Vec3 dir) {
		ServerLevel world = player.level();
		Vec3 eye = player.getEyePosition();
		Vec3 start = new Vec3(eye.x + dir.x * 0.5, eye.y - 0.1 + dir.y * 0.5, eye.z + dir.z * 0.5);
		Projectile projectile = switch (p.projectile()) {
			case SMALL_FIREBALL -> new PlayerSmallFireball(world, player, dir);
			case FIREBALL -> new PlayerFireball(world, player, dir, p.explosionPowerAt(level));
			case DRAGON_FIREBALL -> new PlayerDragonFireball(world, player, dir);
			case WITHER_SKULL -> new PlayerWitherSkull(world, player, dir);
			case WIND_CHARGE -> {
				WindCharge charge = new WindCharge(player, world, start.x, start.y, start.z);
				charge.shoot(dir.x, dir.y, dir.z, 1.5F, 1.0F);
				yield charge;
			}
			case SNOWBALL -> {
				PlayerSnowball snowball = new PlayerSnowball(world, player, Math.max(0.0F, p.damageAt(level)));
				snowball.shoot(dir.x, dir.y, dir.z, 1.5F, 1.0F);
				yield snowball;
			}
			case LLAMA_SPIT -> {
				float damage = p.damageAt(level);
				PlayerLlamaSpit spit = new PlayerLlamaSpit(world, player, damage < 0.0F ? 1.0F : damage);
				spit.shoot(dir.x, dir.y, dir.z, 1.5F, 1.0F);
				yield spit;
			}
			case ARROW -> {
				Arrow arrow = new Arrow(world, player, new ItemStack(Items.ARROW), null);
				arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
				float damage = p.damageAt(level);
				if (damage >= 0.0F) arrow.setBaseDamage(damage);
				arrow.shoot(dir.x, dir.y, dir.z, 3.0F, 1.0F);
				yield arrow;
			}
			default -> throw new IllegalStateException("not a flying projectile: " + p.projectile());
		};
		projectile.setPos(start.x, start.y, start.z);
		return projectile;
	}

	/** Nearest hostile in front of the player (the crosshair entity first), within range, cone and line of sight. */
	static @Nullable LivingEntity shulkerTarget(ServerPlayer player, @Nullable Entity crosshair) {
		if (crosshair instanceof LivingEntity living && validShulkerTarget(player, living)) return living;
		return player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(AbsorbCaps.SHULKER_BULLET_RANGE),
						e -> validShulkerTarget(player, e)).stream()
				.min(Comparator.comparingDouble(player::distanceToSqr))
				.orElse(null);
	}

	private static boolean validShulkerTarget(ServerPlayer player, LivingEntity e) {
		if (!(e instanceof Enemy) || !e.isAlive() || e.isSpectator() || e == player) return false;
		Vec3 to = e.getBoundingBox().getCenter().subtract(player.getEyePosition());
		double distance = to.length();
		if (distance > AbsorbCaps.SHULKER_BULLET_RANGE || distance < 1.0E-3) return false;
		double cos = to.scale(1.0 / distance).dot(player.getViewVector(1.0F));
		return cos >= Math.cos(Math.toRadians(SHULKER_CONE_DEGREES / 2.0)) && player.hasLineOfSight(e);
	}

	/** Evoker fangs in a line along the horizontal look direction, 1 block apart, each on the ground (like the evoker). */
	private static boolean fangs(ServerPlayer player, Vec3 look, int count) {
		double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
		float yaw = horizontal < 1.0E-4 ? player.getYRot() * Mth.DEG_TO_RAD + Mth.HALF_PI : (float) Mth.atan2(look.z, look.x);
		double minY = player.getY() - 3.0;
		double maxY = player.getY() + 1.0;
		boolean any = false;
		for (int i = 0; i < count; i++) {
			double reach = 1.25 + i;
			any |= fang(player, player.getX() + Mth.cos(yaw) * reach, player.getZ() + Mth.sin(yaw) * reach, minY, maxY, yaw, i);
		}
		return any;
	}

	/** Evoker#createSpellEntity: the first block with a sturdy top at or below maxY, down to minY. */
	private static boolean fang(ServerPlayer player, double x, double z, double minY, double maxY, float yaw, int delay) {
		ServerLevel level = player.level();
		BlockPos pos = BlockPos.containing(x, maxY, z);
		do {
			BlockPos below = pos.below();
			if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
				double top = 0.0;
				if (!level.isEmptyBlock(pos)) {
					BlockState state = level.getBlockState(pos);
					VoxelShape shape = state.getCollisionShape(level, pos);
					if (!shape.isEmpty()) top = shape.max(Direction.Axis.Y);
				}
				level.addFreshEntity(new EvokerFangs(level, x, pos.getY() + top, z, yaw, delay, player));
				level.gameEvent(GameEvent.ENTITY_PLACE, new Vec3(x, pos.getY() + top, z), GameEvent.Context.of(player));
				return true;
			}
			pos = pos.below();
		} while (pos.getY() >= Mth.floor(minY) - 1);
		return false;
	}

	/**
	 * Of the vanilla block reactions to a projectile ({@code BlockState#onProjectileHit}), the ones that change no block
	 * and light nothing: target (redstone pulse), bell, amethyst chime, big dripleaf tilt.
	 */
	static boolean harmlessBlockReaction(BlockState state) {
		Block block = state.getBlock();
		return block instanceof TargetBlock || block instanceof BellBlock || block instanceof AmethystBlock || block instanceof BigDripleafBlock;
	}

	/** {@code Projectile#onHitBlock} limited to {@link #harmlessBlockReaction}. */
	static void safeBlockReaction(Projectile projectile, BlockHitResult hit) {
		BlockState state = projectile.level().getBlockState(hit.getBlockPos());
		if (harmlessBlockReaction(state)) state.onProjectileHit(projectile.level(), state, hit, projectile);
	}

	/** The shooting player, or null when gone (then the blast spares every player). */
	static @Nullable ServerPlayer shooter(Projectile projectile) {
		return projectile.getOwner() instanceof ServerPlayer player ? player : null;
	}

	/** Ghast fireball: 6 impact damage like vanilla, a safe {@link AbilitySupport#explode} blast, no block changes. Not saved. */
	static final class PlayerFireball extends Fireball {
		private final float power;

		PlayerFireball(Level level, LivingEntity owner, Vec3 direction, float power) {
			super(EntityTypes.FIREBALL, owner, direction, level);
			this.power = power;
		}

		@Override
		protected void onHit(HitResult hit) {
			super.onHit(hit);
			if (level() instanceof ServerLevel serverLevel && !isRemoved()) {
				if (power > 0.0F) {
					AbilitySupport.explode(serverLevel, this, damageSources().explosion(this, getOwner()), shooter(this), getX(), getY(), getZ(), power);
				}
				discard();
			}
		}

		@Override
		protected void onHitBlock(BlockHitResult hit) {
			safeBlockReaction(this, hit);
		}

		@Override
		protected void onHitEntity(EntityHitResult hit) {
			super.onHitEntity(hit);
			if (level() instanceof ServerLevel serverLevel) {
				Entity target = hit.getEntity();
				DamageSource source = damageSources().fireball(this, getOwner());
				target.hurtServer(serverLevel, source, 6.0F);
				EnchantmentHelper.doPostAttackEffects(serverLevel, target, source);
			}
		}

		@Override
		public boolean shouldBeSaved() {
			return false;
		}
	}

	/**
	 * Blaze fireball that sets the entity it hits on fire but never places fire blocks (vanilla always would for a
	 * player owner, ignoring spawn protection, adventure mode and claims) and changes no block. Not saved.
	 */
	static final class PlayerSmallFireball extends SmallFireball {
		PlayerSmallFireball(Level level, LivingEntity owner, Vec3 direction) {
			super(level, owner, direction);
		}

		@Override
		protected void onHitBlock(BlockHitResult hit) {
			safeBlockReaction(this, hit);
		}

		@Override
		public boolean shouldBeSaved() {
			return false;
		}
	}

	/** Dragon fireball (vanilla breath cloud) that changes no block. Not saved. */
	static final class PlayerDragonFireball extends DragonFireball {
		PlayerDragonFireball(Level level, LivingEntity owner, Vec3 direction) {
			super(level, owner, direction);
		}

		@Override
		protected void onHitBlock(BlockHitResult hit) {
			safeBlockReaction(this, hit);
		}

		@Override
		public boolean shouldBeSaved() {
			return false;
		}
	}

	/**
	 * Wither skull (reserved kind) whose blast never breaks blocks, whatever mobGriefing says: vanilla hit damage and
	 * wither effect, then an {@link AbilitySupport#explode} blast (power 1) instead of vanilla's
	 * {@code ExplosionInteraction.MOB} one. Never dangerous (the blue skull). Not saved.
	 */
	static final class PlayerWitherSkull extends WitherSkull {
		PlayerWitherSkull(Level level, LivingEntity owner, Vec3 direction) {
			super(level, owner, direction);
		}

		@Override
		public void setDangerous(boolean value) {
			super.setDangerous(false);
		}

		/** Projectile#onHit's dispatch (without its projectile-redirect branch), then our blast instead of WitherSkull's. */
		@Override
		protected void onHit(HitResult hit) {
			if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
				onHitEntity(entityHit);
				level().gameEvent(GameEvent.PROJECTILE_LAND, hit.getLocation(), GameEvent.Context.of(this, null));
			} else if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
				onHitBlock(blockHit);
				BlockPos pos = blockHit.getBlockPos();
				level().gameEvent(GameEvent.PROJECTILE_LAND, pos, GameEvent.Context.of(this, level().getBlockState(pos)));
			}
			if (level() instanceof ServerLevel serverLevel && !isRemoved()) {
				AbilitySupport.explode(serverLevel, this, damageSources().explosion(this, getOwner()), shooter(this), getX(), getY(), getZ(), 1.0F);
				discard();
			}
		}

		@Override
		protected void onHitBlock(BlockHitResult hit) {
			safeBlockReaction(this, hit);
		}

		@Override
		public boolean shouldBeSaved() {
			return false;
		}
	}

	/** Snowball dealing {@code damage} (vanilla: 0, blazes 3). Not saved. */
	static final class PlayerSnowball extends Snowball {
		private final float damage;

		PlayerSnowball(Level level, LivingEntity owner, float damage) {
			super(level, owner, new ItemStack(Items.SNOWBALL));
			this.damage = damage;
		}

		@Override
		protected void onHitEntity(EntityHitResult hit) {
			Entity target = hit.getEntity();
			float amount = target.getType() == EntityTypes.BLAZE ? Math.max(3.0F, damage) : damage;
			if (level() instanceof ServerLevel serverLevel) target.hurtServer(serverLevel, damageSources().thrown(this, getOwner()), amount);
		}

		@Override
		public boolean shouldBeSaved() {
			return false;
		}
	}

	/** Llama spit dealing {@code damage} (vanilla: 1). Not saved. */
	static final class PlayerLlamaSpit extends LlamaSpit {
		private final float damage;

		PlayerLlamaSpit(Level level, LivingEntity owner, float damage) {
			super(EntityTypes.LLAMA_SPIT, level);
			this.damage = damage;
			setOwner(owner);
			setPos(owner.getX(), owner.getEyeY() - 0.1, owner.getZ());
		}

		@Override
		protected void onHitEntity(EntityHitResult hit) {
			if (getOwner() instanceof LivingEntity owner && level() instanceof ServerLevel serverLevel) {
				Entity target = hit.getEntity();
				DamageSource source = damageSources().spit(this, owner);
				if (target.hurtServer(serverLevel, source, damage)) EnchantmentHelper.doPostAttackEffects(serverLevel, target, source);
			}
		}

		@Override
		public boolean shouldBeSaved() {
			return false;
		}
	}
}
