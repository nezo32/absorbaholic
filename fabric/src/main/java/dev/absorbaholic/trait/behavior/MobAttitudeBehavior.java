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
import dev.absorbaholic.trait.TargetFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * {@code absorbaholic:mob_attitude}: how the mobs in {@code entities} (ids, #tags, words) treat the player.
 * <ul>
 * <li>{@code hostile}: every 20 ticks, matching mobs within {@code radius} (max 64) that see the player and have no
 *     target attack it: {@code setTarget}, persistent anger for neutral mobs (wolves, endermen, bees …), the
 *     {@code ATTACK_TARGET} memory for brain mobs (piglins …). Mobs without an attack simply do nothing. When the
 *     entry goes away (removed, mode OFF, death, logout) the mobs it provoked and the player did not hurt calm down.</li>
 * <li>{@code flee}: every 10 ticks, matching {@link PathfinderMob}s within {@code radius} path away
 *     ({@code DefaultRandomPos.getPosAway(mob, 16, 7, player)}, speed ×1.2, like {@code AvoidEntityGoal}); while inside
 *     the radius no matching mob may target the player. With {@code pause_on_hit} &gt; 0, hurting any matching mob
 *     suspends the fear for {@code pause_on_hit} ticks. Bosses, the Warden and the Ravager never flee, nor do creepers
 *     unless their type is listed directly (never through a tag), whatever a datapack puts in the list.</li>
 * <li>{@code ignore}: matching mobs never acquire the player as a target on their own (setTarget / canAttack are
 *     refused, which also stops the enderman's stare-aggro and brain mobs), except in revenge: the player hurt that mob,
 *     or one of the same type within 16 blocks, in the last 200 ticks.</li>
 * </ul>
 * Never affects players, NoAI mobs, owned / tamed animals, mobs leashed to the player, or bosses unless listed by id.
 * No mob AI goals are added, so nothing leaks when the trait goes away. A radius &lt;= 0 disables the entry at that level.
 *
 * <pre>{"type": "absorbaholic:mob_attitude", "entities": ["#absorbaholic:fears_golem"], "attitude": "flee", "radius": [4, 6, 8], "pause_on_hit": 100}</pre>
 */
public final class MobAttitudeBehavior implements Behavior<MobAttitudeBehavior.Params> {
	/** The attitude of the matching mobs. */
	public enum Attitude implements StringRepresentable {
		HOSTILE("hostile"),
		FLEE("flee"),
		IGNORE("ignore");

		public static final Codec<Attitude> CODEC = StringRepresentable.fromEnum(Attitude::values);
		private final String name;

		Attitude(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public record Params(TargetFilter entities, Attitude attitude, Optional<LevelValue> radius, int pauseOnHit, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				TargetFilter.CODEC.fieldOf("entities").forGetter(Params::entities),
				Attitude.CODEC.fieldOf("attitude").forGetter(Params::attitude),
				LevelValue.CODEC.optionalFieldOf("radius").forGetter(Params::radius),
				Codec.intRange(0, AbsorbCaps.BEHAVIOR_MAX_TICK_INTERVAL).optionalFieldOf("pause_on_hit", 0).forGetter(Params::pauseOnHit),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			if (entities.isAny()) return DataResult.error(() -> "\"entities\" must not be empty");
			if (attitude != Attitude.IGNORE && radius.isEmpty()) return DataResult.error(() -> "attitude " + attitude.getSerializedName() + " needs \"radius\"");
			if (pauseOnHit > 0 && attitude != Attitude.FLEE) return DataResult.error(() -> "\"pause_on_hit\" only works with attitude flee");
			return DataResult.success(this);
		}

		/** The radius at {@code level}, capped at {@link AbsorbCaps#MOB_SCAN_MAX_RADIUS}; &lt;= 0 = disabled. */
		double radiusAt(int level) {
			return Math.min(radius.map(r -> r.at(level)).orElse(0.0), AbsorbCaps.MOB_SCAN_MAX_RADIUS);
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("mob_attitude", Params.CODEC, new MobAttitudeBehavior());

	@Override
	public int tickInterval(Params p) {
		return switch (p.attitude()) {
			case HOSTILE -> AbsorbCaps.MOB_HOSTILE_INTERVAL_TICKS;
			case FLEE -> AbsorbCaps.MOB_FLEE_INTERVAL_TICKS;
			case IGNORE -> 200; // nothing to do periodically
		};
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		if (p.attitude() == Attitude.IGNORE) return;
		double radius = p.radiusAt(self.level());
		EntryStates.State state = EntryStates.get(player, self);
		if (p.attitude() == Attitude.HOSTILE) MobRules.prune(state.provoked, player);
		if (!(radius > 0.0) || !EntryStates.condition(player, self, p.condition())) return;
		if (p.attitude() == Attitude.HOSTILE) {
			provokeAll(p, player, radius, state);
		} else if (state.pausedUntil <= EntryStates.now(player)) {
			fleeAll(p, player, radius);
		}
	}

	@Override
	public void onDeactivate(ActiveBehavior<Params> self, ServerPlayer player) {
		EntryStates.State state = EntryStates.peek(player, self);
		if (state != null) MobRules.calmAll(state.provoked, player);
		EntryStates.remove(player, self);
	}

	@Override
	public void onDealtDamage(ActiveBehavior<Params> self, ServerPlayer player, LivingEntity target, DamageSource source, float amount) {
		Params p = self.params();
		if (p.attitude() == Attitude.HOSTILE || !p.entities().test(target)) return;
		EntryStates.State state = EntryStates.get(player, self);
		long now = EntryStates.now(player);
		if (p.attitude() == Attitude.FLEE) {
			if (p.pauseOnHit() > 0) state.pausedUntil = now + p.pauseOnHit();
		} else {
			state.hits.addLast(new EntryStates.Hit(target.getUUID(), target.getType(), target.position(), now));
			while (state.hits.size() > AbsorbCaps.MOB_REVENGE_MAX_HITS) state.hits.removeFirst();
		}
	}

	@Override
	public boolean preventsTargeting(ActiveBehavior<Params> self, ServerPlayer player, Mob mob) {
		Params p = self.params();
		if (p.attitude() == Attitude.HOSTILE || !p.entities().test(mob) || !MobRules.affectable(mob, player, p.entities())) return false;
		if (p.attitude() == Attitude.FLEE) {
			double radius = p.radiusAt(self.level());
			if (!(radius > 0.0) || !MobRules.mayFlee(mob, p.entities()) || mob.distanceToSqr(player) > radius * radius) return false;
			if (EntryStates.get(player, self).pausedUntil > EntryStates.now(player)) return false;
		} else if (revenge(EntryStates.get(player, self), mob, EntryStates.now(player))) {
			return false;
		}
		return EntryStates.condition(player, self, p.condition());
	}

	private static void provokeAll(Params p, ServerPlayer player, double radius, EntryStates.State state) {
		for (Mob mob : MobRules.scan(player, Mob.class, radius, m -> p.entities().test(m) && MobRules.affectable(m, player, p.entities()))) {
			if (mob.getTarget() != null || !mob.hasLineOfSight(player)) continue;
			if (MobRules.provoke(mob, player)) state.provoked.add(mob);
		}
	}

	private static void fleeAll(Params p, ServerPlayer player, double radius) {
		Vec3 from = player.position();
		for (Mob mob : MobRules.scan(player, Mob.class, radius,
				m -> p.entities().test(m) && MobRules.affectable(m, player, p.entities()) && MobRules.mayFlee(m, p.entities())
						&& m.getLastHurtByMob() != player)) { // revenge: the engine lets it fight back, so does the fear
			forget(mob, player);
			if (mob instanceof PathfinderMob walker) runAway(walker, from);
		}
	}

	/** The mob drops the player as its target and stops being angry at it. */
	private static void forget(Mob mob, ServerPlayer player) {
		if (mob.getTargetUnchecked() == player) mob.setTarget(null);
		if (mob instanceof NeutralMob neutral && mob.level() instanceof ServerLevel level && neutral.isAngryAt(player, level)) {
			neutral.stopBeingAngry();
		}
		if (MobRules.rawTarget(mob) == player) MobRules.calm(mob, player);
	}

	/** Paths away from {@code from} unless the mob is already heading somewhere farther from it. */
	private static void runAway(PathfinderMob mob, Vec3 from) {
		PathNavigation nav = mob.getNavigation();
		Path path = nav.getPath();
		double mobDistance = mob.position().distanceToSqr(from);
		if (!nav.isDone() && path != null) {
			BlockPos goal = path.getTarget();
			if (goal != null && Vec3.atBottomCenterOf(goal).distanceToSqr(from) > mobDistance) return;
		}
		Vec3 away = DefaultRandomPos.getPosAway(mob, AbsorbCaps.MOB_FLEE_MAX_HORIZONTAL, AbsorbCaps.MOB_FLEE_MAX_VERTICAL, from);
		if (away != null && away.distanceToSqr(from) > mobDistance) nav.moveTo(away.x, away.y, away.z, AbsorbCaps.MOB_FLEE_SPEED);
	}

	private static boolean revenge(EntryStates.State state, Mob mob, long now) {
		for (EntryStates.Hit hit : state.hits) {
			if (now - hit.tick() > AbsorbCaps.MOB_REVENGE_TICKS) continue;
			if (hit.mob().equals(mob.getUUID())) return true;
			if (hit.type() == mob.getType() && hit.pos().distanceToSqr(mob.position()) <= AbsorbCaps.MOB_REVENGE_RADIUS * AbsorbCaps.MOB_REVENGE_RADIUS) return true;
		}
		return false;
	}
}
