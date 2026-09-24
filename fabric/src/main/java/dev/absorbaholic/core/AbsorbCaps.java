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

	/** At most this much weakness damage (direct + weakness-multiplier extra) per rolling window: 4 hearts. */
	public static final float WEAKNESS_DAMAGE_BUDGET = 8.0F;
	/** Length of the rolling window of the weakness damage budget. */
	public static final int WEAKNESS_DAMAGE_WINDOW_TICKS = 20;
	/** A single weakness hit never takes a player who is at full health below this health. */
	public static final float WEAKNESS_MIN_HEALTH_FROM_FULL = 1.0F;

	// ---- damage / heal / food / effect factor caps -------------------------------------------------------------

	/** Combined incoming-damage multiplier from traits never goes below this (max 75 % reduction) per source. */
	public static final float DAMAGE_TAKEN_FLOOR = 0.25F;
	/** Combined incoming-damage multiplier from weaknesses never exceeds this (the extra then goes through the gate). */
	public static final float DAMAGE_TAKEN_WEAKNESS_CEILING = 3.0F;
	/** Combined outgoing (dealt) damage multiplier range from traits and weaknesses. */
	public static final float DAMAGE_DEALT_MIN = 0.25F;
	public static final float DAMAGE_DEALT_MAX = 3.0F;
	/** Combined healing multiplier range. */
	public static final float HEAL_FACTOR_MIN = 0.25F;
	public static final float HEAL_FACTOR_MAX = 3.0F;
	/** Combined food exhaustion multiplier range. */
	public static final float EXHAUSTION_FACTOR_MIN = 0.0F;
	public static final float EXHAUSTION_FACTOR_MAX = 4.0F;
	/** Combined mob detection (visibility) multiplier range: &gt; 1 = mobs notice the player from further away. */
	public static final double VISIBILITY_FACTOR_MIN = 0.25;
	public static final double VISIBILITY_FACTOR_MAX = 3.0;
	/** Combined experience gain multiplier range. */
	public static final float EXPERIENCE_FACTOR_MIN = 0.0F;
	public static final float EXPERIENCE_FACTOR_MAX = 3.0F;
	/** Combined item durability-loss multiplier range. */
	public static final float DURABILITY_FACTOR_MIN = 0.0F;
	public static final float DURABILITY_FACTOR_MAX = 4.0F;
	/** Max amplifier (0-based) an effect may reach after a weakness amplifies it. */
	public static final int EFFECT_AMPLIFIER_MAX = 4;

	/**
	 * Damage types a trait immunity can never block (in addition to everything in
	 * {@code #minecraft:bypasses_invulnerability}): /kill, the void, and generic damage.
	 */
	public static final List<String> NEVER_IMMUNE_DAMAGE_TYPES = List.of(
			"minecraft:generic_kill", "minecraft:out_of_world", "minecraft:generic");

	// ---- active abilities --------------------------------------------------------------------------------------

	/** Every active ability has its own cooldown; never shorter than this. */
	public static final int ABILITY_MIN_COOLDOWN_TICKS = 20;
	/** Hard server-side limits of any active ability. */
	public static final double ABILITY_MAX_RANGE = 24.0;
	public static final int ABILITY_MAX_TARGETS = 8;
	public static final double TELEPORT_MAX_DISTANCE = 16.0;
	/** Launch / dash / flight-burst velocity cap (blocks per tick). */
	public static final double ABILITY_MAX_VELOCITY = 1.6;
	/** Explosion power cap of detonate / fireball style abilities (TNT is 4). */
	public static final float ABILITY_MAX_EXPLOSION_POWER = 3.0F;

	// ---- behaviors in general ----------------------------------------------------------------------------------

	/** Smallest allowed tick interval of periodic behaviors (e.g. rain / sunlight damage checks). */
	public static final int BEHAVIOR_MIN_TICK_INTERVAL = 1;
	/** How often the engine re-applies attribute clamps (other modifiers such as sprinting change the final value). */
	public static final int ATTRIBUTE_RECLAMP_INTERVAL_TICKS = 20;

	// ---- client visuals ----------------------------------------------------------------------------------------

	/** Aura particles are spawned every this many ticks per player with traits (per client, own setting permitting). */
	public static final int AURA_PARTICLE_INTERVAL_TICKS = 8;
	/** Players need this many total trait levels for a full-strength aura. */
	public static final int AURA_FULL_STRENGTH_LEVELS = 20;

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
