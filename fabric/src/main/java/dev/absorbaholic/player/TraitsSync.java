package dev.absorbaholic.player;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.net.TraitsPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sends a player's own {@link TraitsPayload} (openScreen=false) on join / respawn ({@code PlayerLifecycle}) and after
 * every {@link TraitsChangedCallback}; {@link #openScreen} sends someone's traits with openScreen=true
 * ({@code /absorbaholic traits [player]}). Only to clients that registered the payload channel, so vanilla clients
 * are never sent anything they would be disconnected for. Server thread only.
 */
public final class TraitsSync {
	/** Must match the name codec of {@link TraitsPayload}. */
	private static final int MAX_NAME_LENGTH = 64;

	private TraitsSync() {}

	public static void register() {
		TraitsChangedCallback.EVENT.register((player, before, after) -> {
			try {
				send(player);
			} catch (RuntimeException e) {
				// never break the change itself (absorption, command) over a failed sync
				Absorbaholic.LOGGER.error("Could not send Absorbaholic traits to {}", player.getScoreboardName(), e);
			}
		});
	}

	/** Sends {@code player}'s traits to {@code player} (no-op for clients without the mod). */
	public static void send(ServerPlayer player) {
		if (!canReceive(player)) return;
		ServerPlayNetworking.send(player, payload(player, false));
	}

	/** Opens the traits screen of {@code owner} on {@code viewer}'s client; false if the viewer has no mod. */
	public static boolean openScreen(ServerPlayer viewer, ServerPlayer owner) {
		if (!canReceive(viewer)) return false;
		ServerPlayNetworking.send(viewer, payload(owner, true));
		return true;
	}

	/** True when {@code player} is connected with a client that accepts {@link TraitsPayload}. */
	public static boolean canReceive(ServerPlayer player) {
		return player.connection != null && ServerPlayNetworking.canSend(player, TraitsPayload.TYPE);
	}

	private static TraitsPayload payload(ServerPlayer owner, boolean openScreen) {
		String name = owner.getScoreboardName();
		if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH);
		return new TraitsPayload(owner.getUUID(), name, PlayerData.traits(owner), openScreen);
	}
}
