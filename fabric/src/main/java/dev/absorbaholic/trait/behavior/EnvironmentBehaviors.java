package dev.absorbaholic.trait.behavior;

/**
 * WP-BEH-B. Behavior group: weather, light, water, fire, temperature, food, healing, effects (tick, heal, exhaustion, effect hooks; direct damage via WeaknessDamage). One class per behavior type in this package, registered here with
 * {@code BehaviorRegistry.register("<type_path>", Params.CODEC, new XBehavior())}.
 */
public final class EnvironmentBehaviors {
	private EnvironmentBehaviors() {}

	public static void register() {
		// TODO(WP-BEH-B)
	}
}
