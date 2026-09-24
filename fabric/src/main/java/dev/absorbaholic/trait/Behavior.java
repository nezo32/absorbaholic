package dev.absorbaholic.trait;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
	 * Client-physics flags this entry grants ({@link MovementFlags}); OR-ed over all active entries and synced to the
	 * owning client, because players move client side. Read once when the active set is built.
	 */
	default int movementFlags(ActiveBehavior<P> self) {
		return 0;
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

	/** Jump key pressed (edge, from the client's input packet; also mid-air), not sneaking. */
	default void onJump(ActiveBehavior<P> self, ServerPlayer player) {}

	/** Jump key pressed while sneaking. */
	default void onSneakJump(ActiveBehavior<P> self, ServerPlayer player) {}

	/** Attack key swung at nothing (air) while sneaking (sent by the client mod). */
	default void onSneakSwing(ActiveBehavior<P> self, ServerPlayer player) {}

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

	/** Factor on healing. */
	default float healFactor(ActiveBehavior<P> self, ServerPlayer player, float amount) {
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
}
