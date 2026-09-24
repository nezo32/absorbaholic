package dev.absorbaholic.player;

/**
 * WP-PLAYER. Death / respawn / join handling:
 * <ul>
 * <li>ServerPlayerEvents.COPY_FROM(old, new, alive): alive (End exit) → always copy traits; death → copy iff the
 *     world's keepOnDeath is on AND no active behavior of the old player {@code wipesOnDeath} (dragon egg; evaluated
 *     by {@code TraitEngine.wipesOnDeath(old)} BEFORE the old entity is discarded, i.e. using its cached ActiveSet);
 *     a wipe logs and tells the player (Texts.tr("absorbaholic.message.wiped")).</li>
 * <li>ServerPlayerEvents.AFTER_RESPAWN / JOIN: mark the engine dirty; send traits, world state, discovered set.</li>
 * </ul>
 */
public final class PlayerLifecycle {
	private PlayerLifecycle() {}

	public static void register() {
		// TODO(WP-PLAYER)
	}
}
