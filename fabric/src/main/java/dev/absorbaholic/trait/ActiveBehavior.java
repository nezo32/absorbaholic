package dev.absorbaholic.trait;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * One behavior entry active on one player: the configured entry, its effective level (&gt;= 1), the source it came
 * from and whether it belongs to the weakness side. It is the {@code self} argument of every {@link Behavior} hook and
 * forwards each hook with the right type parameter, so the engine can iterate {@code ActiveBehavior<?>} arrays.
 * Built by the engine when a player's traits change; immutable.
 *
 * @param tickInterval {@link Behavior#tickInterval} for these params (&gt;= 1), cached
 * @param tickPhase stagger offset so periodic behaviors of many players do not run on the same tick
 */
public record ActiveBehavior<P>(BehaviorEntry<P> entry, int level, Identifier sourceId, boolean weakness, int tickInterval, int tickPhase) {
	public static <P> ActiveBehavior<P> of(BehaviorEntry<P> entry, int level, Identifier sourceId, boolean weakness, int tickPhase) {
		int interval = Math.max(1, entry.type().behavior().tickInterval(entry.params()));
		return new ActiveBehavior<>(entry, level, sourceId, weakness, interval, tickPhase);
	}

	public P params() {
		return entry.params();
	}

	public BehaviorType<P> type() {
		return entry.type();
	}

	private Behavior<P> b() {
		return entry.type().behavior();
	}

	public boolean has(Hook hook) {
		return entry.type().has(hook);
	}

	/** True when {@link #tick} is due at server tick {@code tick}. */
	public boolean dueAt(long tick) {
		return (tick + tickPhase) % tickInterval == 0;
	}

	public void tick(ServerPlayer p) { b().tick(this, p); }

	public void onActivate(ServerPlayer p) { b().onActivate(this, p); }

	public void onDeactivate(ServerPlayer p) { b().onDeactivate(this, p); }

	public MovementState movement() { return b().movement(this); }

	public boolean isImmuneTo(ServerPlayer p, DamageSource s) { return b().isImmuneTo(this, p, s); }

	public float incomingDamageFactor(ServerPlayer p, DamageSource s, float amount) { return b().incomingDamageFactor(this, p, s, amount); }

	public float outgoingDamageFactor(ServerPlayer p, LivingEntity target, DamageSource s, float amount) {
		return b().outgoingDamageFactor(this, p, target, s, amount);
	}

	public void onDealtDamage(ServerPlayer p, LivingEntity target, DamageSource s, float amount) { b().onDealtDamage(this, p, target, s, amount); }

	public void onAttacked(ServerPlayer p, DamageSource s, float amount) { b().onAttacked(this, p, s, amount); }

	public void onKill(ServerPlayer p, LivingEntity victim) { b().onKill(this, p, victim); }

	public void onJump(ServerPlayer p) { b().onJump(this, p); }

	public boolean onSneakJump(ServerPlayer p) { return b().onSneakJump(this, p); }

	public boolean onAirJump(ServerPlayer p) { return b().onAirJump(this, p); }

	public boolean onSneakDoubleTap(ServerPlayer p) { return b().onSneakDoubleTap(this, p); }

	public boolean onSneakSwing(ServerPlayer p, @Nullable Entity target) { return b().onSneakSwing(this, p, target); }

	public void onSneakAttack(ServerPlayer p, Entity target) { b().onSneakAttack(this, p, target); }

	public void onLand(ServerPlayer p, double fallDistance) { b().onLand(this, p, fallDistance); }

	public void onDeath(ServerPlayer p, DamageSource s) { b().onDeath(this, p, s); }

	public boolean wipesOnDeath() { return b().wipesOnDeath(this); }

	public float healFactor(ServerPlayer p, float amount, boolean natural) { return b().healFactor(this, p, amount, natural); }

	public float exhaustionFactor(ServerPlayer p, float amount) { return b().exhaustionFactor(this, p, amount); }

	public boolean allowEffect(ServerPlayer p, MobEffectInstance e) { return b().allowEffect(this, p, e); }

	public MobEffectInstance modifyEffect(ServerPlayer p, MobEffectInstance e) { return b().modifyEffect(this, p, e); }

	public boolean preventsTargeting(ServerPlayer p, Mob mob) { return b().preventsTargeting(this, p, mob); }

	public double visibilityFactor(ServerPlayer p, @Nullable Entity looker) { return b().visibilityFactor(this, p, looker); }

	public void onBlockBreak(ServerPlayer p, BlockPos pos, BlockState state) { b().onBlockBreak(this, p, pos, state); }

	public void onHitByProjectile(ServerPlayer p, Projectile projectile) { b().onHitByProjectile(this, p, projectile); }

	public FoodProperties modifyFood(ServerPlayer p, ItemStack stack, FoodProperties food) { return b().modifyFood(this, p, stack, food); }

	public void onItemConsumed(ServerPlayer p, ItemStack stack) { b().onItemConsumed(this, p, stack); }

	public float experienceFactor(ServerPlayer p, int amount) { return b().experienceFactor(this, p, amount); }

	public float durabilityFactor(ServerPlayer p, ItemStack stack, int amount) { return b().durabilityFactor(this, p, stack, amount); }
}
