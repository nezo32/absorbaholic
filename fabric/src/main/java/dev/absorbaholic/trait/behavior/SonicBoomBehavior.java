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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * {@code absorbaholic:sonic_boom} (warden), trigger {@code sneak_swing} (at air or at an entity): {@code damage} [L],
 * {@code range} [L] (capped at {@link AbsorbCaps#SONIC_BOOM_MAX_RANGE}), {@code cooldown} [L]. Like the Warden: a ray
 * from the eye up to {@code range} hits the <b>first</b> living entity whose box it crosses, through blocks, and only
 * that one. It takes {@code damage} of the vanilla {@code sonic_boom} type attributed to the player (bypasses armor
 * and shields; PvP rules apply) and is knocked back 2.5 horizontally / 0.5 vertically × (1 - knockback resistance).
 * Sonic boom particles run along the ray with the warden sound. No target: particles only and half the cooldown
 * (never below {@link AbsorbCaps#ABILITY_MIN_COOLDOWN_TICKS}).
 */
public final class SonicBoomBehavior implements Behavior<SonicBoomBehavior.Params> {
	public record Params(LevelValue damage, LevelValue range, LevelValue cooldown, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("damage").forGetter(Params::damage),
				LevelValue.CODEC.fieldOf("range").forGetter(Params::range),
				LevelValue.CODEC.fieldOf("cooldown").forGetter(Params::cooldown),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));

		/** Ray length at {@code level}, capped (&lt;= 0 = inactive). */
		public double rangeAt(int level) {
			return Math.min(AbsorbCaps.SONIC_BOOM_MAX_RANGE, AbilitySupport.at(range, level));
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("sonic_boom", Params.CODEC, new SonicBoomBehavior());

	@Override
	public boolean onSneakSwing(ActiveBehavior<Params> self, ServerPlayer player, @Nullable Entity crosshair) {
		Params p = self.params();
		double range = p.rangeAt(self.level());
		float damage = (float) AbilitySupport.at(p.damage(), self.level());
		if (range <= 0.0 || damage <= 0.0F || !AbilitySupport.ready(player, self) || !p.condition().test(player)) return false;

		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();
		Vec3 dir = player.getViewVector(1.0F);
		LivingEntity target = firstTarget(player, eye, dir, range);
		double length = target == null ? range : Math.min(range, target.getBoundingBox().getCenter().distanceTo(eye));
		for (int i = 1; i <= Math.floor(length); i++) {
			Vec3 at = eye.add(dir.scale(i));
			level.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 3.0F, 1.0F);

		double cooldown = AbilitySupport.at(p.cooldown(), self.level());
		if (target == null) {
			AbilitySupport.startCooldown(player, self, cooldown / 2.0);
			return true;
		}
		if (target.hurtServer(level, player.damageSources().sonicBoom(player), damage)) {
			double resistance = target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
			double vertical = 0.5 * (1.0 - resistance);
			double horizontal = 2.5 * (1.0 - resistance);
			target.push(dir.x * horizontal, dir.y * vertical, dir.z * horizontal);
			AbilitySupport.syncMotion(target);
		}
		AbilitySupport.startCooldown(player, self, cooldown);
		return true;
	}

	/** The first living entity whose box the ray crosses (entities only: blocks do not stop it). */
	static @Nullable LivingEntity firstTarget(ServerPlayer player, Vec3 eye, Vec3 dir, double range) {
		Vec3 end = eye.add(dir.scale(range));
		AABB box = player.getBoundingBox().expandTowards(dir.scale(range)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box,
				e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator() && e.isPickable() && !player.isPassengerOfSameVehicle(e),
				range * range);
		return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
	}
}
