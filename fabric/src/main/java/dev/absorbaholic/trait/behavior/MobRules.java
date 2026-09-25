package dev.absorbaholic.trait.behavior;

import java.util.List;
import java.util.Set;

import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.trait.TargetFilter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.phys.AABB;

/**
 * Shared rules of the mob-affecting behaviors (behaviors.md §0.6): which mobs may be affected at all, the bounded
 * scan, and how a mob is made to target the player ("provoke") or to forget it again ("calm"). Server thread only.
 */
final class MobRules {
	private MobRules() {}

	/** Ender Dragon and Wither. */
	static boolean isBoss(Entity e) {
		return e instanceof EnderDragon || e instanceof WitherBoss;
	}

	/**
	 * General exclusions of §0.6: dead or NoAI mobs, tamed / owned animals (any owner), mobs leashed to this player,
	 * and bosses unless {@code filter} names their type directly.
	 */
	static boolean affectable(Mob mob, ServerPlayer player, TargetFilter filter) {
		if (!mob.isAlive() || mob.isNoAi() || mob.isRemoved()) return false;
		if (mob instanceof OwnableEntity owned && owned.getOwnerReference() != null) return false;
		if (mob.isLeashed() && mob.getLeashHolder() == player) return false;
		return !isBoss(mob) || filter.namesDirectly(mob.getType());
	}

	/**
	 * Hard exclusions of {@code mob_attitude} flee, even if a datapack adds these mobs to the list or tag: bosses, the
	 * Warden and the Ravager never flee; a creeper flees only when its type is listed directly (the cat's trait), never
	 * through a tag such as {@code #absorbaholic:fears_golem} or a word.
	 */
	static boolean mayFlee(Mob mob, TargetFilter filter) {
		if (isBoss(mob) || mob.getType() == EntityTypes.WARDEN || mob.getType() == EntityTypes.RAVAGER) return false;
		return !(mob instanceof Creeper) || filter.namesDirectly(mob.getType());
	}

	/**
	 * A mob that attacks players on its own (detection_range "hostile mobs"): an {@link Enemy} that is not a neutral mob
	 * (endermen, zombified piglins …), not the Warden (its own anger system) and not a piglin the player's gold keeps calm.
	 */
	static boolean huntsPlayers(Mob mob, ServerPlayer player) {
		if (!(mob instanceof Enemy) || mob instanceof NeutralMob || mob.getType() == EntityTypes.WARDEN) return false;
		return !(mob instanceof AbstractPiglin) || mob.getType() == EntityTypes.PIGLIN_BRUTE || !PiglinAi.isWearingSafeArmor(player);
	}

	/** Mobs of class {@code type} whose bounding box is within {@code radius} of the player's, capped per scan. */
	static <T extends Mob> List<T> scan(ServerPlayer player, Class<T> type, double radius, java.util.function.Predicate<T> filter) {
		AABB box = player.getBoundingBox().inflate(radius);
		double maxSq = radius * radius;
		List<T> found = player.level().getEntitiesOfClass(type, box, m -> m.distanceToSqr(player) <= maxSq && filter.test(m));
		return found.size() > AbsorbCaps.MOB_SCAN_MAX_MOBS ? found.subList(0, AbsorbCaps.MOB_SCAN_MAX_MOBS) : found;
	}

	/** The mob's current target, including one it is not allowed to keep (so a stale target can be cleared). */
	static LivingEntity rawTarget(Mob mob) {
		LivingEntity target = mob.getTargetUnchecked();
		if (target != null) return target;
		Brain<?> brain = mob.getBrain();
		return brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
				? brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null)
				: null;
	}

	/**
	 * Makes {@code mob} target the player: {@code setTarget}, neutral mobs get angry at the player (persistent anger),
	 * brain-driven mobs (piglins, hoglins …) get the {@code ATTACK_TARGET} memory. Returns false if the mob may not attack
	 * the player (peaceful, creative, another trait's ignore …).
	 */
	static boolean provoke(Mob mob, ServerPlayer player) {
		if (!mob.canAttack(player)) return false;
		mob.setTarget(player);
		if (mob instanceof NeutralMob neutral) {
			neutral.setPersistentAngerTarget(EntityReference.of(player));
			neutral.startPersistentAngerTimer();
		}
		Brain<?> brain = mob.getBrain();
		if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
			brain.setMemory(MemoryModuleType.ATTACK_TARGET, player);
		}
		return true;
	}

	/**
	 * Undoes {@link #provoke} if the mob still targets the player and the player did not hurt it (then the fight is the
	 * player's own doing and vanilla keeps it).
	 */
	static void calm(Mob mob, ServerPlayer player) {
		if (!mob.isAlive() || mob.getLastHurtByMob() == player) return;
		if (mob instanceof NeutralMob neutral && mob.level() instanceof ServerLevel level && neutral.isAngryAt(player, level)) {
			neutral.stopBeingAngry();
		}
		if (mob.getTargetUnchecked() == player) mob.setTarget(null);
		Brain<?> brain = mob.getBrain();
		if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)
				&& brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == player) {
			brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
		}
	}

	/** Calms every mob in {@code provoked} and forgets them. */
	static void calmAll(Set<Mob> provoked, ServerPlayer player) {
		for (Mob mob : provoked) calm(mob, player);
		provoked.clear();
	}

	/** Forgets provoked mobs that are gone or no longer target the player. */
	static void prune(Set<Mob> provoked, ServerPlayer player) {
		provoked.removeIf(m -> m.isRemoved() || !m.isAlive() || rawTarget(m) != player);
	}
}
