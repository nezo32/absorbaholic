package dev.absorbaholic.trait;

import net.minecraft.core.BlockPos;
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
 * Custom logic of a trait or weakness, one stateless singleton per behavior type (see {@link BehaviorType}). Every hook
 * is optional; the {@link TraitEngine} calls only the hooks a class overrides ({@link Hook}, detected once at
 * registration), only on the server thread, and only while the player {@linkplain TraitEngine#isActive is active}
 * (mode ON, survival/adventure, alive, not a FakePlayer).
 *
 * <p>{@code self} carries the entry's params, its level (trait level for a trait, weakness level for a weakness; a
 * weakness at level 0 is never active), the source id and which side it is on. Per-player mutable state (cooldowns,
 * counters) goes in {@code PlayerRuntime}, never in the behavior object.
 *
 * <p>Multiplicative hooks return a factor (1 = no change); the engine multiplies the factors of all active entries
 * and applies the caps of {@code AbsorbCaps}: traits can only reduce incoming damage (floor 0.25), weaknesses can only
 * amplify it and that extra goes through the weakness damage gate. Direct weakness damage must use
 * {@link WeaknessDamage#hurt}, never {@code player.hurtServer} directly.
 *
 * @param <P> the params record decoded by the type's codec
 */
public interface Behavior<P> {
	/** How often {@link #tick} runs, in ticks (staggered per player). Read once when the active set is built. */
	default int tickInterval(P params) {
		return 1;
	}

	/** Periodic logic, every {@link #tickInterval} ticks. */
	default void tick(ActiveBehavior<P> self, ServerPlayer player) {}

	/** The entry became active (absorbed, level changed, mode ON, respawn, join). Apply persistent side effects. */
	default void onActivate(ActiveBehavior<P> self, ServerPlayer player) {}

	/** The entry stopped being active (removed, level changed, mode OFF, creative, death, logout). Undo side effects. */
	default void onDeactivate(ActiveBehavior<P> self, ServerPlayer player) {}

	/**
	 * Client-physics state this entry grants ({@link MovementFlags} + speeds); merged over all active entries and
	 * synced to the owning client, because players move client side. Read once when the active set is built.
	 */
	default MovementState movement(ActiveBehavior<P> self) {
		return MovementState.NONE;
	}

	/** True = the player takes no damage from {@code source}. Ignored for never-immune damage (see AbsorbCaps). */
	default boolean isImmuneTo(ActiveBehavior<P> self, ServerPlayer player, DamageSource source) {
		return false;
	}

	/** Factor on damage the player takes ({@code amount} is before armor, after difficulty scaling). */
	default float incomingDamageFactor(ActiveBehavior<P> self, ServerPlayer player, DamageSource source, float amount) {
		return 1.0F;
	}

	/** Factor on damage the player deals to {@code target} (any damage whose attacker is the player). */
	default float outgoingDamageFactor(ActiveBehavior<P> self, ServerPlayer player, LivingEntity target, DamageSource source, float amount) {
		return 1.0F;
	}

	/** After the player damaged {@code target} (set on fire, wither, lifesteal …). */
	default void onDealtDamage(ActiveBehavior<P> self, ServerPlayer player, LivingEntity target, DamageSource source, float amount) {}

	/** After the player took damage (thorns …); {@code source.getEntity()} is the attacker, if any. */
	default void onAttacked(ActiveBehavior<P> self, ServerPlayer player, DamageSource source, float amount) {}

	/** The player killed {@code victim}. */
	default void onKill(ActiveBehavior<P> self, ServerPlayer player, LivingEntity victim) {}

	/** Jump key pressed on the ground, not sneaking (rising edge of the client's input). Not an ability trigger. */
	default void onJump(ActiveBehavior<P> self, ServerPlayer player) {}

	// ---- ability triggers (behaviors.md §0.4): return true if the ability FIRED (it was ready and did something).
	// The engine offers a trigger to entries in trait acquisition order and stops at the first that fires ("one
	// trigger → one ability"), then charges AbsorbCaps.ABILITY_EXHAUSTION (air jump: AIR_JUMP_EXHAUSTION).

	/** Trigger {@code sneak_jump}: jump rising edge while sneaking and on the ground. */
	default boolean onSneakJump(ActiveBehavior<P> self, ServerPlayer player) {
		return false;
	}

	/** Trigger {@code air_jump}: jump rising edge while airborne. */
	default boolean onAirJump(ActiveBehavior<P> self, ServerPlayer player) {
		return false;
	}

	/** Trigger {@code sneak_double_tap}: two sneak rising edges within {@code AbsorbCaps.SNEAK_DOUBLE_TAP_TICKS}, on ground. */
	default boolean onSneakDoubleTap(ActiveBehavior<P> self, ServerPlayer player) {
		return false;
	}

	/**
	 * Trigger {@code sneak_swing}: a swing while sneaking with no block in reach (server-detected from the swing /
	 * punch packet, so it works on vanilla clients); {@code target} = the entity in reach under the crosshair, if any.
	 */
	default boolean onSneakSwing(ActiveBehavior<P> self, ServerPlayer player, @Nullable Entity target) {
		return false;
	}

	/** The player attacked {@code target} while sneaking. */
	default void onSneakAttack(ActiveBehavior<P> self, ServerPlayer player, Entity target) {}

	/** The player landed after falling {@code fallDistance} blocks (before fall damage). */
	default void onLand(ActiveBehavior<P> self, ServerPlayer player, double fallDistance) {}

	/** The player died (before the new player entity copies traits). */
	default void onDeath(ActiveBehavior<P> self, ServerPlayer player, DamageSource source) {}

	/** True = any death wipes all of the player's traits (dragon egg), regardless of keep-on-death. */
	default boolean wipesOnDeath(ActiveBehavior<P> self) {
		return false;
	}

	/** Factor on healing; {@code natural} = natural regeneration from food (FoodData#tick). */
	default float healFactor(ActiveBehavior<P> self, ServerPlayer player, float amount, boolean natural) {
		return 1.0F;
	}

	/** Factor on food exhaustion. */
	default float exhaustionFactor(ActiveBehavior<P> self, ServerPlayer player, float amount) {
		return 1.0F;
	}

	/** False = the effect is not applied at all (immunity to blindness …). */
	default boolean allowEffect(ActiveBehavior<P> self, ServerPlayer player, MobEffectInstance effect) {
		return true;
	}

	/** Replace an effect that is about to be applied (poison amplified …). Return {@code effect} for no change. */
	default MobEffectInstance modifyEffect(ActiveBehavior<P> self, ServerPlayer player, MobEffectInstance effect) {
		return effect;
	}

	/** True = {@code mob} may not pick the player as its target (unless the player hurt it). */
	default boolean preventsTargeting(ActiveBehavior<P> self, ServerPlayer player, Mob mob) {
		return false;
	}

	/** Factor on the distance at which mobs notice the player (&gt; 1 = from further away). */
	default double visibilityFactor(ActiveBehavior<P> self, ServerPlayer player, @Nullable Entity looker) {
		return 1.0;
	}

	/** The player broke a block. */
	default void onBlockBreak(ActiveBehavior<P> self, ServerPlayer player, BlockPos pos, BlockState state) {}

	/** The player was hit by {@code projectile} (also zero-damage ones such as snowballs). */
	default void onHitByProjectile(ActiveBehavior<P> self, ServerPlayer player, Projectile projectile) {}

	/** Replace the food value the player is about to receive from {@code stack}. Return {@code food} for no change. */
	default FoodProperties modifyFood(ActiveBehavior<P> self, ServerPlayer player, ItemStack stack, FoodProperties food) {
		return food;
	}

	/** The player finished eating / drinking {@code stack} (a copy taken before consumption). */
	default void onItemConsumed(ActiveBehavior<P> self, ServerPlayer player, ItemStack stack) {}

	/** Factor on experience points the player gains from experience orbs (never commands / enchanting). */
	default float experienceFactor(ActiveBehavior<P> self, ServerPlayer player, int amount) {
		return 1.0F;
	}

	/** Factor on durability damage to the player's items. */
	default float durabilityFactor(ActiveBehavior<P> self, ServerPlayer player, ItemStack stack, int amount) {
		return 1.0F;
	}
}
