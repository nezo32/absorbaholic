package dev.absorbaholic.trait.behavior;

/**
 * WP-BEH-B. Behavior group: the player's relation to the entities around it (mob attitudes, detection range, auras on
 * nearby mobs, the item magnet). One class per behavior type in this package; each registers itself in its
 * {@code TYPE} initializer, which {@link #register} triggers. Shared mob rules: {@link MobRules}.
 */
public final class MobBehaviors {
	private MobBehaviors() {}

	public static void register() {
		MobAttitudeBehavior.TYPE.id();
		DetectionRangeBehavior.TYPE.id();
		AuraBehavior.TYPE.id();
		ItemMagnetBehavior.TYPE.id();
	}
}
