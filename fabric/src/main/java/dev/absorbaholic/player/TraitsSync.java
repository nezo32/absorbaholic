package dev.absorbaholic.player;

import net.minecraft.server.level.ServerPlayer;

/**
 * WP-PLAYER. Sends a player's own {@code TraitsPayload} (openScreen=false) on join and after every
 * {@link TraitsChangedCallback}; {@link #openScreen} sends someone's traits with openScreen=true
 * ({@code /absorbaholic traits [player]}). Only to clients that can receive the payload.
 */
public final class TraitsSync {
	private TraitsSync() {}

	public static void register() {
		// TODO(WP-PLAYER): TraitsChangedCallback.EVENT.register((p, before, after) -> send(p));
	}

	/** Sends {@code player}'s traits to {@code player}. */
	public static void send(ServerPlayer player) {
		// TODO(WP-PLAYER)
	}

	/** Opens the traits screen of {@code owner} on {@code viewer}'s client; false if the viewer has no mod. */
	public static boolean openScreen(ServerPlayer viewer, ServerPlayer owner) {
		// TODO(WP-PLAYER)
		return false;
	}
}
