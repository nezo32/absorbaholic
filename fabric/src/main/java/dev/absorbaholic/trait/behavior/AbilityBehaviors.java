package dev.absorbaholic.trait.behavior;

/**
 * WP-BEH-C. Behavior group: active abilities fired by the engine's triggers (sneak_jump, sneak_double_tap,
 * sneak_swing) or by being hurt, each with its own cooldown in {@code PlayerRuntime.abilityCooldowns} (see
 * {@link AbilitySupport}) and the AbsorbCaps ability limits. Touching each TYPE registers it.
 */
public final class AbilityBehaviors {
	private AbilityBehaviors() {}

	public static void register() {
		TeleportBehavior.TYPE.id();
		SneakDetonateBehavior.TYPE.id();
		ShootProjectileBehavior.TYPE.id();
		SonicBoomBehavior.TYPE.id();
	}
}
