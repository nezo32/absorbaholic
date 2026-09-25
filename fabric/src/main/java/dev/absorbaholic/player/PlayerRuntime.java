package dev.absorbaholic.player;

import java.util.HashMap;
import java.util.Map;

import dev.absorbaholic.absorb.AbsorbChannel;
import dev.absorbaholic.core.DamageGate;
import dev.absorbaholic.trait.ActiveSet;
import dev.absorbaholic.trait.MovementState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Input;
import org.jspecify.annotations.Nullable;

/**
 * Transient per-player server state (the non-persistent {@code absorbaholic:runtime} attachment; a new player entity
 * after respawn starts fresh). Server thread only. Fields are grouped by the work package that owns them; others
 * read them only through that package's API.
 */
public final class PlayerRuntime {
	// ---- WP-ENGINE (TraitEngine) ----
	/** Cached active behaviors; {@link ActiveSet#EMPTY} while dormant. */
	public ActiveSet active = ActiveSet.EMPTY;
	/** True = rebuild {@link #active} and attribute modifiers on the next engine tick. */
	public boolean dirty = true;
	/** Engine's view of "active" (mode ON, survival/adventure, alive) at the last tick; a flip marks dirty. */
	public boolean wasActive;
	/** Last client input seen (edge detection for jump / sneak-jump hooks). */
	public Input lastInput = Input.EMPTY;
	/** Weakness damage limiter. */
	public final DamageGate damageGate = new DamageGate();
	/** Game time until which the player's burning was started by a weakness (charged to {@link #damageGate}). */
	public long weaknessFireUntil = Long.MIN_VALUE / 2;
	/** Raw saved / carried-over health to restore once our max-health modifiers are back (NaN = none). */
	public float pendingHealth = Float.NaN;
	/** Per-ability cooldown end (game time), keyed by behavior type id + source id; see TraitEngine. */
	public final Map<Identifier, Long> abilityCooldowns = new HashMap<>();
	/** Game time of the previous sneak rising edge (sneak_double_tap). */
	public long lastSneakEdge = Long.MIN_VALUE / 2;
	/** Effects our status_effect behaviors applied, by effect id → owning source id (never modified by effect_modifier). */
	public final Map<Identifier, Identifier> ownedEffects = new HashMap<>();
	/** Free per-behavior scratch state, keyed like abilityCooldowns (charges, timers, counters). */
	public final Map<Identifier, Object> behaviorState = new HashMap<>();
	/** Last synced movement state and aura (to send only changes); null = never sent. */
	public @Nullable MovementState sentMovement;
	public int sentAuraColor = -1;
	public float sentAuraStrength = -1.0F;

	// ---- WP-ABSORB ----
	/** The running channel, or null. */
	public @Nullable AbsorbChannel channel;
	/** Game time until which the player cannot absorb again. */
	public long absorbCooldownUntil;
}
