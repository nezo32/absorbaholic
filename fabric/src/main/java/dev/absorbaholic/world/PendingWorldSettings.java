package dev.absorbaholic.world;

/**
 * Duck interface on {@code LevelStorageSource.LevelStorageAccess} (see LevelStorageAccessMixin).
 * The Create World screen stores the button value on the access object of the world it just created;
 * the integrated server that is handed that same object consumes it on SERVER_STARTING.
 */
public interface PendingWorldSettings {
	void absorbaholic$setPendingEnabled(boolean enabled);

	/** Returns and clears the pending value; null if none (existing world, dedicated server). */
	Boolean absorbaholic$takePendingEnabled();
}
