package dev.absorbaholic.registry;

/**
 * WP-REG. Sends {@code SourcesSyncPayload} (every {@link SourceSummary}) to a player on
 * ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS (join and /reload), only if the client can receive it.
 */
public final class SourceSync {
	private SourceSync() {}

	public static void register() {
		// TODO(WP-REG)
	}
}
