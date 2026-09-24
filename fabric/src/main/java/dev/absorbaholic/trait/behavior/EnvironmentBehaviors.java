package dev.absorbaholic.trait.behavior;

/**
 * WP-BEH-B. Behavior group: weather, light, water, fire, temperature, food, healing, effects (tick, heal, exhaustion,
 * food and effect hooks; direct damage via WeaknessDamage). One class per behavior type in this package; each
 * registers itself in its {@code TYPE} initializer, which {@link #register} triggers.
 */
public final class EnvironmentBehaviors {
	private EnvironmentBehaviors() {}

	public static void register() {
		StatusEffectBehavior.TYPE.id();
		EnvironmentDamageBehavior.TYPE.id();
		HealMultiplierBehavior.TYPE.id();
		HealOverTimeBehavior.TYPE.id();
		HungerDrainBehavior.TYPE.id();
		FoodModifierBehavior.TYPE.id();
		EffectModifierBehavior.TYPE.id();
	}
}
