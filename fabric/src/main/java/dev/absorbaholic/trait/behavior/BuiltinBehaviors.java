package dev.absorbaholic.trait.behavior;

import dev.absorbaholic.trait.BehaviorType;

/**
 * Registers every built-in behavior type. Each group registrar is owned by one work package (see ARCHITECTURE.md
 * "File ownership"); adding a behavior = one class in that group's package + one line in its registrar + lang
 * entries. Called from Absorbaholic#onInitialize before the source registry.
 */
public final class BuiltinBehaviors {
	/** Reference behaviors written with the skeleton (the SPEC example and the dragon egg). */
	public static final BehaviorType<DamageMultiplierBehavior.Params> DAMAGE_MULTIPLIER = DamageMultiplierBehavior.TYPE;
	public static final BehaviorType<?> WIPE_ON_DEATH = WipeOnDeathBehavior.TYPE;

	private BuiltinBehaviors() {}

	public static void register() {
		// the two reference types register in their static initializers (referenced above)
		CombatBehaviors.register();
		EnvironmentBehaviors.register();
		MovementBehaviors.register();
		AbilityBehaviors.register();
		MobBehaviors.register();
		MiscBehaviors.register();
	}
}
