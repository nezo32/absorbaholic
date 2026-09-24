package dev.absorbaholic.world;

import net.minecraft.server.level.ServerPlayer;

/**
 * WP-PLAYER. Keeps clients' view of the world settings current: {@code WorldStatePayload} to everyone on
 * {@link WorldSettingsEvents#CHANGED} and to a player on join; {@code DiscoveredPayload} (replace=true, full set) on
 * join and (replace=false, one id) to everyone on {@link WorldSettingsEvents#DISCOVERED}. Only to modded clients.
 * With hints off the discovered set is still sent (the client hides the hint itself).
 */
public final class WorldStateSync {
	private WorldStateSync() {}

	public static void register() {
		// TODO(WP-PLAYER)
	}

	/** Full world state + discovered set to one player (join). */
	public static void sendAll(ServerPlayer player) {
		// TODO(WP-PLAYER)
	}
}
