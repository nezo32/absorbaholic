package dev.absorbaholic.trait.behavior;

import java.util.List;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.trait.BehaviorType;

/**
 * WP-BEH-A. Behavior group: everything else (experience, durability, death / wipe): {@code xp_multiplier},
 * {@code durability_multiplier} ({@code wipe_on_death} registers itself, see BuiltinBehaviors).
 */
public final class MiscBehaviors {
	/** Every type of this group, in registration order. */
	public static final List<BehaviorType<?>> TYPES = List.of(
			XpMultiplierBehavior.TYPE,
			DurabilityMultiplierBehavior.TYPE);

	private MiscBehaviors() {}

	/** Registers the group (touching {@link #TYPES} initializes, and so registers, every type). */
	public static void register() {
		Absorbaholic.LOGGER.debug("Registered {} misc behavior types", TYPES.size());
	}
}
