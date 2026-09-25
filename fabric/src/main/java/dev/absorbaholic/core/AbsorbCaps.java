package dev.absorbaholic.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every tuning number and safety cap of Absorbaholic in one place (documented in the README). Pure constants, no
 * Minecraft types: ids are plain strings. Per-world settings (mode, keep-on-death, hints, max traits) live in
 * {@code AbsorbWorldSettings}; the values here are their defaults and bounds.
 */
public final class AbsorbCaps {
	private AbsorbCaps() {}

	// ---- absorbing ---------------------------------------------------------------------------------------------

	/** Hold time of the absorb channel: 1.5 s. */
	public static final int CHANNEL_TICKS = 30;
	/** Per-player cooldown after each successful absorption: 10 s. */
	public static final int COOLDOWN_TICKS = 200;
	/** A mob can be absorbed only at or below this fraction of its max health. */
	public static final float MOB_HEALTH_THRESHOLD = 0.25F;
	/** Extra reach tolerance (blocks) over the player's interaction range while channeling (lag / movement). */
	public static final double CHANNEL_RANGE_TOLERANCE = 1.0;
	/**
	 * World protection: {@code #absorbaholic:bedrock_protected} blocks (bedrock) cannot be absorbed in the bottom this-many
	 * layers of a dimension, nor at / above {@code minY + logicalHeight - this} in a dimension with a ceiling (Nether roof).
	 */
	public static final int PROTECTED_LAYERS = 5;

	// ---- rolls -------------------------------------------------------------------------------------------------

	/** Chance of a pure absorption (trait +1, weakness +0). Checked first: r &lt; PURE_CHANCE. */
	public static final double PURE_CHANCE = 0.02;
	/** Chance of a mutation (50/50 trait +2 / weakness +2). Checked second: r &lt; PURE_CHANCE + MUTATION_CHANCE. */
	public static final double MUTATION_CHANCE = 0.15;

	// ---- levels and slots --------------------------------------------------------------------------------------

	/** max_level of a source when the JSON omits it. */
	public static final int DEFAULT_MAX_LEVEL = 3;
	/** Allowed range of a source's max_level. */
	public static final int MIN_MAX_LEVEL = 1;
	public static final int MAX_MAX_LEVEL = 5;
	/** Default per-world limit of distinct sources per player ({@code /absorbaholic max}). */
	public static final int DEFAULT_MAX_TRAITS = 20;
	public static final int MIN_MAX_TRAITS = 1;
	public static final int MAX_MAX_TRAITS = 64;

	// ---- weakness damage gate ----------------------------------------------------------------------------------

	/**
	 * At most this much weakness damage (direct + weakness-multiplier extra + weakness-caused burning / starvation) per
	 * rolling window: 4 hearts. The effective budget of a player is {@code min(this, maxHealth - WEAKNESS_MIN_HEALTH_FROM_FULL)}.
	 */
	public static final float WEAKNESS_DAMAGE_BUDGET = 8.0F;
	/** Length of the rolling window of the weakness damage budget. */
	public static final int WEAKNESS_DAMAGE_WINDOW_TICKS = 20;
	/**
	 * A player who was at full health at any point of the current window is never taken below this health by weakness
	 * damage in that window (all weakness hits of the window together, not just one).
	 */
	public static final float WEAKNESS_MIN_HEALTH_FROM_FULL = 1.0F;

	// ---- damage / heal / food / effect factor caps -------------------------------------------------------------

	/** Combined incoming-damage multiplier from traits never goes below this (max 75 % reduction) per source. */
	public static final float DAMAGE_TAKEN_FLOOR = 0.25F;
	/** Combined incoming-damage multiplier from weaknesses never exceeds this (the extra then goes through the gate). */
	public static final float DAMAGE_TAKEN_WEAKNESS_CEILING = 3.0F;
	/** Combined outgoing (dealt) damage multiplier range (behaviors.md damage_dealt_multiplier). */
	public static final float DAMAGE_DEALT_MIN = 0.5F;
	public static final float DAMAGE_DEALT_MAX = 2.0F;
	/** Combined healing multiplier range (heal_multiplier). */
	public static final float HEAL_FACTOR_MIN = 0.25F;
	public static final float HEAL_FACTOR_MAX = 2.0F;
	/** Combined food exhaustion multiplier range (hunger_drain). */
	public static final float EXHAUSTION_FACTOR_MIN = 0.25F;
	public static final float EXHAUSTION_FACTOR_MAX = 5.0F;
	/** Combined mob detection (visibility) multiplier range: &gt; 1 = mobs notice the player from further away. */
	public static final double VISIBILITY_FACTOR_MIN = 0.25;
	public static final double VISIBILITY_FACTOR_MAX = 3.0;
	/** Combined experience-orb multiplier range (xp_multiplier). */
	public static final float EXPERIENCE_FACTOR_MIN = 0.25F;
	public static final float EXPERIENCE_FACTOR_MAX = 2.5F;
	/** Combined item durability-loss multiplier range (durability_multiplier). */
	public static final float DURABILITY_FACTOR_MIN = 0.5F;
	public static final float DURABILITY_FACTOR_MAX = 3.0F;
	/** Max amplifier (0-based) an effect may reach after a weakness amplifies it. */
	public static final int EFFECT_AMPLIFIER_MAX = 4;
	/** Combined knockback-taken multiplier range (knockback_multiplier); vanilla knockback resistance applies after it. */
	public static final float KNOCKBACK_FACTOR_MIN = 0.5F;
	public static final float KNOCKBACK_FACTOR_MAX = 3.0F;

	/**
	 * Damage types a trait immunity can never block (in addition to everything in
	 * {@code #minecraft:bypasses_invulnerability}): /kill, the void, and generic damage.
	 */
	public static final List<String> NEVER_IMMUNE_DAMAGE_TYPES = List.of(
			"minecraft:generic_kill", "minecraft:out_of_world", "minecraft:generic");
	/** Damage type tags a {@code damage_multiplier} of 0 (immunity) may name (behaviors.md). */
	public static final List<String> IMMUNITY_TAGS = List.of(
			"minecraft:is_fire", "minecraft:is_fall", "minecraft:is_drowning", "minecraft:is_freezing");
	/** Damage type ids a {@code damage_multiplier} of 0 (immunity) may name. */
	public static final List<String> IMMUNITY_TYPES = List.of(
			"minecraft:hot_floor", "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:lightning_bolt", "minecraft:ender_pearl");

	// ---- active abilities --------------------------------------------------------------------------------------

	/** Every active ability has its own cooldown; never shorter than this. */
	public static final int ABILITY_MIN_COOLDOWN_TICKS = 20;
	/** Hard server-side limits of any active ability. */
	public static final double ABILITY_MAX_RANGE = 24.0;
	public static final int ABILITY_MAX_TARGETS = 8;
	public static final double TELEPORT_MAX_DISTANCE = 16.0;
	public static final double SONIC_BOOM_MAX_RANGE = 20.0;
	public static final int EVOKER_FANGS_MAX = 16;
	/** Launch / dash / flight-burst velocity cap (blocks per tick). */
	public static final double ABILITY_MAX_VELOCITY = 1.6;
	/** Explosion power cap of detonate style abilities (TNT is 4); fireballs have their own lower cap. */
	public static final float ABILITY_MAX_EXPLOSION_POWER = 3.0F;
	public static final float FIREBALL_MAX_EXPLOSION_POWER = 2.0F;
	/** Food exhaustion charged per fired ability (behaviors.md §0.4); air jump costs less. */
	public static final float ABILITY_EXHAUSTION = 1.0F;
	public static final float AIR_JUMP_EXHAUSTION = 0.5F;
	/** Two sneak rising edges within this many ticks = sneak_double_tap. */
	public static final int SNEAK_DOUBLE_TAP_TICKS = 8;
	/** Flight: exhaustion per flying tick; slow falling granted when flight is lost mid-air. */
	public static final float FLIGHT_EXHAUSTION_PER_TICK = 0.01F;
	public static final int FLIGHT_LOSS_SLOW_FALLING_TICKS = 200;
	/** Flight: allowed flying speed (vanilla creative flight is 0.05). */
	public static final float FLIGHT_MIN_SPEED = 0.001F;
	public static final float FLIGHT_MAX_SPEED = 1.0F;
	/** sneak_swing fires at most once per this many ticks (the 26.2 client sends a swing packet on every click). */
	public static final int SNEAK_SWING_MIN_INTERVAL_TICKS = 4;
	/** Teleport: attempts of the random (chorus) search. */
	public static final int TELEPORT_RANDOM_ATTEMPTS = 16;
	/** Teleport (look mode): a landing spot may be at most this many blocks below the view ray. */
	public static final int TELEPORT_LOOK_MAX_DROP = 3;

	// ---- ability caps ------------------------------------------------------------------------------------------

	/** Longest sneak_detonate fuse (10 s). */
	public static final int SNEAK_DETONATE_MAX_FUSE_TICKS = 200;
	/** Shulker bullet target search radius (shoot_projectile). */
	public static final double SHULKER_BULLET_RANGE = 16.0;
	/** Vanilla evoker fang damage: the base of shoot_projectile's evoker_fangs {@code damage} factor. */
	public static final float EVOKER_FANG_BASE_DAMAGE = 6.0F;
	/**
	 * Gravity used to credit an air_jump burst against the fall distance: a burst of vertical speed v can lift the
	 * player at most v² / (2 g) blocks (vanilla player gravity, drag ignored), and only that much is taken off.
	 */
	public static final double AIR_JUMP_FALL_CREDIT_GRAVITY = 0.08;
	/** air_jump: most charges a level may grant. */
	public static final int AIR_JUMP_MAX_CHARGES = 64;

	// ---- behavior-specific caps (behaviors.md) -----------------------------------------------------------------

	/** Tick behaviors evaluate their condition at most this often (cached in between). */
	public static final int CONDITION_CACHE_TICKS = 10;
	/** Max {@code condition_radius} of near_entity. */
	public static final double CONDITION_MAX_RADIUS = 16.0;
	/** {@code condition_radius} of near_entity when the JSON omits it. */
	public static final double CONDITION_DEFAULT_RADIUS = 8.0;
	/** Mob-affecting scans never look further than this. */
	public static final double MOB_SCAN_MAX_RADIUS = 64.0;
	/** Mob-affecting scans handle at most this many mobs (bounded work even in a mob farm). */
	public static final int MOB_SCAN_MAX_MOBS = 48;
	/** detection_range: how often the provoke scan runs (ticks). */
	public static final int DETECTION_SCAN_INTERVAL_TICKS = 20;
	/** mob_attitude: scan interval of hostile and of flee (ticks). */
	public static final int MOB_HOSTILE_INTERVAL_TICKS = 20;
	public static final int MOB_FLEE_INTERVAL_TICKS = 10;
	/** mob_attitude flee: AvoidEntityGoal's random position away from the player (horizontal / vertical) and speed. */
	public static final int MOB_FLEE_MAX_HORIZONTAL = 16;
	public static final int MOB_FLEE_MAX_VERTICAL = 7;
	public static final double MOB_FLEE_SPEED = 1.2;
	/** mob_attitude ignore: revenge lasts this long, covers mobs of the hurt type this close, remembers this many hits. */
	public static final int MOB_REVENGE_TICKS = 200;
	public static final double MOB_REVENGE_RADIUS = 16.0;
	public static final int MOB_REVENGE_MAX_HITS = 16;
	/** detection_range &gt; 1: provoke radius cap. */
	public static final double DETECTION_MAX_RADIUS = 48.0;
	public static final double AURA_MAX_RADIUS = 16.0;
	public static final double ITEM_MAGNET_MAX_RADIUS = 10.0;
	public static final double ITEM_MAGNET_PULL = 0.25;
	/** item_magnet: pulse interval (ticks), at most this many items per pulse, own throws left alone this long. */
	public static final int ITEM_MAGNET_INTERVAL_TICKS = 5;
	public static final int ITEM_MAGNET_MAX_ITEMS = 64;
	public static final int ITEM_MAGNET_OWN_THROW_TICKS = 40;
	public static final int FROST_WALK_MAX_RADIUS = 5;
	/**
	 * walk_on_fluid solid: a player inside the fluid it walks on (not sneaking) rises at least this fast (blocks per
	 * tick, before gravity) until it stands on the surface. Only where a standable surface exists: a column of source
	 * blocks of that fluid from the feet up to a source with no same fluid above, at most
	 * {@link #FLUID_WALK_SURFACE_SCAN} blocks up (never in falling or flowing fluid).
	 */
	public static final double FLUID_WALK_RISE_SPEED = 0.2;
	public static final int FLUID_WALK_SURFACE_SCAN = 16;
	/**
	 * Landing on a walked fluid surface keeps vanilla's fluid landing: water cancels the fall, lava keeps this share of
	 * the fall distance (vanilla halves it for a tick spent in lava).
	 */
	public static final double FLUID_WALK_LAVA_FALL_FACTOR = 0.5;
	/** sink_in_water terminal downward velocity (blocks per tick, negative). */
	public static final double SINK_MAX_FALL_VELOCITY = -0.3;
	/** retaliate: melee range and per-attacker cooldown; per-attacker cooldowns are pruned above this many attackers. */
	public static final double RETALIATE_MAX_RANGE = 6.0;
	public static final int RETALIATE_COOLDOWN_TICKS = 10;
	public static final int RETALIATE_MAX_TRACKED_ATTACKERS = 32;
	/** kill_reward: victims need at least this max health; at most one reward per this many ticks. */
	public static final float KILL_REWARD_MIN_VICTIM_MAX_HEALTH = 4.0F;
	public static final int KILL_REWARD_COOLDOWN_TICKS = 10;
	/** struck_by: at most once per this many ticks. */
	public static final int STRUCK_BY_COOLDOWN_TICKS = 10;

	// ---- behaviors in general ----------------------------------------------------------------------------------

	/** Allowed tick interval of periodic behaviors (e.g. rain / sunlight damage checks); also the longest pause_on_hit. */
	public static final int BEHAVIOR_MIN_TICK_INTERVAL = 1;
	public static final int BEHAVIOR_MAX_TICK_INTERVAL = 72000;
	/** How often the engine re-applies attribute clamps (other modifiers such as sprinting change the final value). */
	public static final int ATTRIBUTE_RECLAMP_INTERVAL_TICKS = 20;
	/**
	 * status_effect permanent mode: applied duration, refreshed when fewer ticks are left (night vision flickers on the
	 * client below 200 ticks, so it gets longer values); checked every PERMANENT interval, pulse mode every PULSE one.
	 */
	public static final int EFFECT_PERMANENT_DURATION_TICKS = 100;
	public static final int EFFECT_PERMANENT_MIN_LEFT_TICKS = 60;
	public static final int EFFECT_NIGHT_VISION_DURATION_TICKS = 400;
	public static final int EFFECT_NIGHT_VISION_MIN_LEFT_TICKS = 220;
	public static final int EFFECT_PERMANENT_INTERVAL_TICKS = 20;
	public static final int EFFECT_PULSE_INTERVAL_TICKS = 10;

	// ---- network -----------------------------------------------------------------------------------------------

	/** Longest list accepted from / sent to the network (defensive; sources and traits are far below). */
	public static final int NET_MAX_LIST_SIZE = 4096;
	/** Longest source display name synced to clients (must match the name codec of TraitsPayload). */
	public static final int NET_MAX_NAME_LENGTH = 64;

	// ---- client visuals ----------------------------------------------------------------------------------------

	/** Aura particles are spawned every this many ticks per player with traits (per client, own setting permitting). */
	public static final int AURA_PARTICLE_INTERVAL_TICKS = 8;
	/** Players need this many total trait levels for a full-strength aura. */
	public static final int AURA_FULL_STRENGTH_LEVELS = 20;
	/** No aura particles for players farther than this from the local player (blocks). */
	public static final double AURA_PARTICLE_MAX_DISTANCE = 48.0;
	/** Widest absorb hint next to the crosshair and widest row of the traits screen (GUI pixels). */
	public static final int HUD_HINT_MAX_WIDTH = 200;
	public static final int TRAITS_SCREEN_MAX_ROW_WIDTH = 340;

	// ---- attribute clamps --------------------------------------------------------------------------------------

	/**
	 * Clamp of an attribute's FINAL value. Our modifiers are adjusted so the final value stays in range; if the value
	 * without our modifiers is already outside the range we never push it further out.
	 */
	public record Clamp(String attribute, Mode mode, double min, double max) {
		/** ABSOLUTE: [min, max]. BASE_MULTIPLE: [base*min, base*max]. BASE_OFFSET: [base+min, base+max]. */
		public enum Mode { ABSOLUTE, BASE_MULTIPLE, BASE_OFFSET }

		public double lower(double base) {
			return switch (mode) {
				case ABSOLUTE -> min;
				case BASE_MULTIPLE -> base * min;
				case BASE_OFFSET -> base + min;
			};
		}

		public double upper(double base) {
			return switch (mode) {
				case ABSOLUTE -> max;
				case BASE_MULTIPLE -> base * max;
				case BASE_OFFSET -> base + max;
			};
		}
	}

	public static final List<Clamp> CLAMPS = List.of(
			new Clamp("minecraft:movement_speed", Clamp.Mode.BASE_MULTIPLE, 0.4, 2.0),
			new Clamp("minecraft:scale", Clamp.Mode.ABSOLUTE, 0.5, 2.0),
			new Clamp("minecraft:max_health", Clamp.Mode.ABSOLUTE, 6.0, 60.0),
			new Clamp("minecraft:block_interaction_range", Clamp.Mode.ABSOLUTE, 2.5, 8.0),
			new Clamp("minecraft:entity_interaction_range", Clamp.Mode.ABSOLUTE, 2.0, 6.0),
			// 0.2 still clears nothing but lets the player step up slabs / stairs (step_height is clamped separately)
			new Clamp("minecraft:jump_strength", Clamp.Mode.ABSOLUTE, 0.2, 1.2),
			new Clamp("minecraft:step_height", Clamp.Mode.ABSOLUTE, 0.6, 2.0),
			new Clamp("minecraft:armor", Clamp.Mode.ABSOLUTE, 0.0, 30.0),
			new Clamp("minecraft:armor_toughness", Clamp.Mode.ABSOLUTE, 0.0, 20.0),
			new Clamp("minecraft:attack_damage", Clamp.Mode.BASE_OFFSET, -0.5, 20.0),
			new Clamp("minecraft:attack_speed", Clamp.Mode.BASE_MULTIPLE, 0.5, 2.0),
			new Clamp("minecraft:attack_knockback", Clamp.Mode.ABSOLUTE, 0.0, 3.0),
			new Clamp("minecraft:knockback_resistance", Clamp.Mode.ABSOLUTE, 0.0, 1.0),
			new Clamp("minecraft:gravity", Clamp.Mode.ABSOLUTE, 0.02, 0.16),
			new Clamp("minecraft:safe_fall_distance", Clamp.Mode.ABSOLUTE, 1.0, 24.0),
			new Clamp("minecraft:fall_damage_multiplier", Clamp.Mode.ABSOLUTE, 0.0, 3.0),
			new Clamp("minecraft:luck", Clamp.Mode.ABSOLUTE, -5.0, 5.0),
			new Clamp("minecraft:oxygen_bonus", Clamp.Mode.ABSOLUTE, 0.0, 8.0),
			new Clamp("minecraft:burning_time", Clamp.Mode.ABSOLUTE, 0.0, 3.0),
			new Clamp("minecraft:water_movement_efficiency", Clamp.Mode.ABSOLUTE, 0.0, 1.0),
			new Clamp("minecraft:movement_efficiency", Clamp.Mode.ABSOLUTE, 0.0, 1.0),
			new Clamp("minecraft:block_break_speed", Clamp.Mode.BASE_MULTIPLE, 0.25, 4.0),
			new Clamp("minecraft:mining_efficiency", Clamp.Mode.ABSOLUTE, 0.0, 40.0),
			new Clamp("minecraft:submerged_mining_speed", Clamp.Mode.ABSOLUTE, 0.2, 1.0),
			new Clamp("minecraft:sneaking_speed", Clamp.Mode.ABSOLUTE, 0.15, 1.0),
			new Clamp("minecraft:explosion_knockback_resistance", Clamp.Mode.ABSOLUTE, 0.0, 1.0),
			new Clamp("minecraft:max_absorption", Clamp.Mode.ABSOLUTE, 0.0, 20.0));

	private static final Map<String, Clamp> CLAMP_BY_ATTRIBUTE =
			CLAMPS.stream().collect(Collectors.toUnmodifiableMap(Clamp::attribute, Function.identity()));

	/** The clamp of an attribute id ("minecraft:movement_speed"), if any. */
	public static Optional<Clamp> clampFor(String attributeId) {
		return Optional.ofNullable(CLAMP_BY_ATTRIBUTE.get(attributeId));
	}
}
