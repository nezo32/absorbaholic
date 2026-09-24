package dev.absorbaholic.trait;

import dev.absorbaholic.player.PlayerData;
import net.fabricmc.fabric.api.entity.FakePlayer;
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
import org.jspecify.annotations.Nullable;

/**
 * WP-ENGINE. The trait engine: keeps each player's {@link ActiveSet} and attribute modifiers in sync with their
 * traits and the world / game-mode state, and dispatches behavior hooks with the caps of {@code AbsorbCaps}. Every
 * public method is server-thread only and never throws into vanilla code (failures are logged once, like the
 * reference's CustomEffects.safely).
 *
 * <p>Wiring (done in {@link #register}): TraitsChangedCallback / WorldSettingsEvents.CHANGED / AFTER_RESPAWN / JOIN /
 * AFTER_PLAYER_CHANGE_LEVEL → {@link #markDirty}; END_SERVER_TICK → per player: active-state flip check, rebuild when
 * dirty (deactivate old entries, {@link AttributeApplier#apply}, build new set, activate, sync MovementPayload and
 * aura), input edges from {@code getLastClientInput()} (jump on ground → onJump; sneak+jump on ground → sneak_jump
 * trigger; jump while airborne → air_jump trigger; two sneak edges within SNEAK_DOUBLE_TAP_TICKS on ground →
 * sneak_double_tap trigger), due ticks, periodic re-clamp; ServerLivingEntityEvents.ALLOW_DAMAGE → immunity;
 * AFTER_DAMAGE → dealt / attacked; AFTER_DEATH → death hooks; ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY →
 * kill; ServerMobEffectEvents.ALLOW_ADD → allowEffect (skips owned effects); AttackEntityCallback (server, sneaking)
 * → sneak attack; PlayerBlockBreakEvents.AFTER → block break; EntityElytraEvents.CUSTOM → GLIDE (both sides);
 * ServerPlayConnectionEvents.DISCONNECT → deactivate; EntityTrackingEvents.START_TRACKING (player) → send that
 * player's aura. Mixins call the modify* / on* methods below. Triggers go through {@link #fireTrigger}.
 */
public final class TraitEngine {
	private TraitEngine() {}

	public static void register() {
		// TODO(WP-ENGINE)
	}

	/**
	 * Traits and weaknesses do anything only when this is true: world mode ON, survival / adventure (server-side game
	 * mode, not {@code isCreative()}: gametest mock players fake creative), alive, not a FakePlayer.
	 */
	public static boolean isActive(ServerPlayer player) {
		// TODO(WP-ENGINE): AbsorbWorldSettings.isEnabled(server) && player.gameMode.getGameModeForPlayer().isSurvival() && player.isAlive()
		return false;
	}

	/** Rebuild the player's active set and attributes on the next tick. */
	public static void markDirty(ServerPlayer player) {
		if (player instanceof FakePlayer) return;
		PlayerData.runtime(player).dirty = true;
	}

	/** Rebuild now (commands and gametests that need the result synchronously). */
	public static void recompute(ServerPlayer player) {
		// TODO(WP-ENGINE)
	}

	/** The cached active set ({@link ActiveSet#EMPTY} while dormant). */
	public static ActiveSet active(ServerPlayer player) {
		return PlayerData.runtime(player).active;
	}

	// ---- called by mixins / events (identity until WP-ENGINE lands) ----

	/** LivingEntityMixin (victim is the player): trait floor, weakness extra through the damage gate. */
	public static float modifyIncomingDamage(ServerPlayer player, DamageSource source, float amount) {
		return amount;
	}

	/** LivingEntityMixin (attacker is the player). */
	public static float modifyOutgoingDamage(ServerPlayer attacker, LivingEntity target, DamageSource source, float amount) {
		return amount;
	}

	/** {@code natural}: inside FoodData#tick (FoodDataMixin thread-local). */
	public static float modifyHeal(ServerPlayer player, float amount, boolean natural) {
		return amount;
	}

	/** FoodPropertiesMixin: the food value the player is about to receive from {@code stack}. */
	public static FoodProperties modifyFood(ServerPlayer player, ItemStack stack, FoodProperties food) {
		return food;
	}

	/** ProjectileMixin: {@code projectile} hit the player (before damage; also zero-damage projectiles). */
	public static void onHitByProjectile(ServerPlayer player, Projectile projectile) {}

	/** ServerGamePacketListenerImplMixin: the player swung (26.2 swing packet / 26.3 punch packet). */
	public static void onSwing(ServerPlayer player) {}

	/**
	 * Offers an ability trigger to the active entries overriding {@code hook}, in acquisition order; stops at the first
	 * that fires and charges the exhaustion cost. Returns true if one fired.
	 */
	public static boolean fireTrigger(ServerPlayer player, Hook hook, @Nullable Entity target) {
		return false;
	}

	/**
	 * Adds an effect applied by one of our behaviors ({@code status_effect}) and records it as owned by
	 * {@code source}, so effect_modifier never touches it and status_effect only ever removes its own.
	 */
	public static void addOwnedEffect(ServerPlayer player, MobEffectInstance effect, Identifier source) {}

	/** True if {@code effect}'s current instance on the player was applied by our behaviors. */
	public static boolean isOwnedEffect(ServerPlayer player, MobEffectInstance effect) {
		return false;
	}

	public static float modifyExhaustion(ServerPlayer player, float amount) {
		return amount;
	}

	public static MobEffectInstance modifyEffect(ServerPlayer player, MobEffectInstance effect) {
		return effect;
	}

	public static boolean preventsTargeting(Mob mob, ServerPlayer target) {
		return false;
	}

	public static double visibilityFactor(ServerPlayer player, @Nullable Entity looker) {
		return 1.0;
	}

	public static void onLand(ServerPlayer player, double fallDistance) {}

	/** ExperienceOrbMixin: XP from an orb only (stochastic rounding per behaviors.md). */
	public static int modifyExperience(ServerPlayer player, int amount) {
		return amount;
	}

	/** ItemStack durability damage caused while the player holds / wears it. */
	public static int modifyDurabilityDamage(ServerPlayer player, ItemStack stack, int amount) {
		return amount;
	}

	/** After the player finished using a consumable item ({@code stack} = copy before consumption). */
	public static void onItemConsumed(ServerPlayer player, ItemStack stack) {}

	/** Dragon egg: true if any active entry wipes all traits on death. Uses the cached set (call before discard). */
	public static boolean wipesOnDeath(ServerPlayer player) {
		for (ActiveBehavior<?> a : active(player).forHook(Hook.WIPES_ON_DEATH)) {
			if (a.wipesOnDeath()) return true;
		}
		return false;
	}
}
