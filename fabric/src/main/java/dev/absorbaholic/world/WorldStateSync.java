package dev.absorbaholic.world;

import java.util.ArrayList;
import java.util.List;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.net.DiscoveredPayload;
import dev.absorbaholic.net.NetCodecs;
import dev.absorbaholic.net.WorldStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps clients' view of the world settings current: {@code WorldStatePayload} to everyone on
 * {@link WorldSettingsEvents#CHANGED} and to a player on join / respawn; {@code DiscoveredPayload} (replace=true, full
 * set) on join / respawn and (replace=false, one id) to everyone on {@link WorldSettingsEvents#DISCOVERED}. Only to
 * modded clients. With hints off the discovered set is still sent (the client hides the hint itself).
 * Server thread only.
 */
public final class WorldStateSync {
	private WorldStateSync() {}

	public static void register() {
		WorldSettingsEvents.CHANGED.register(WorldStateSync::broadcastState);
		WorldSettingsEvents.DISCOVERED.register((server, source) -> {
			DiscoveredPayload payload = new DiscoveredPayload(false, List.of(source));
			for (ServerPlayer player : server.getPlayerList().getPlayers()) sendIfModded(player, payload);
		});
	}

	/** Full world state + discovered set to one player (join / respawn). */
	public static void sendAll(ServerPlayer player) {
		if (!canReceive(player, WorldStatePayload.TYPE)) return;
		AbsorbWorldSettings settings = AbsorbWorldSettings.get(player.level().getServer());
		ServerPlayNetworking.send(player, WorldStatePayload.of(settings));
		if (!canReceive(player, DiscoveredPayload.TYPE)) return;
		for (DiscoveredPayload payload : discoveredPayloads(List.copyOf(settings.discovered()))) ServerPlayNetworking.send(player, payload);
	}

	/**
	 * The full discovered set as replace=true followed by additions, each within the payload's list limit (one payload
	 * in practice; a world never discovers anywhere near {@link NetCodecs#MAX_LIST} sources).
	 */
	public static List<DiscoveredPayload> discoveredPayloads(List<Identifier> all) {
		List<DiscoveredPayload> out = new ArrayList<>();
		out.add(new DiscoveredPayload(true, all.subList(0, Math.min(all.size(), NetCodecs.MAX_LIST))));
		for (int from = NetCodecs.MAX_LIST; from < all.size(); from += NetCodecs.MAX_LIST) {
			out.add(new DiscoveredPayload(false, all.subList(from, Math.min(all.size(), from + NetCodecs.MAX_LIST))));
		}
		return out;
	}

	private static void broadcastState(MinecraftServer server, AbsorbWorldSettings settings) {
		WorldStatePayload payload = WorldStatePayload.of(settings);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) sendIfModded(player, payload);
	}

	/** Never throws: a failed send to one player must not break the setting change or the absorption behind it. */
	private static void sendIfModded(ServerPlayer player, CustomPacketPayload payload) {
		try {
			if (canReceive(player, payload.type())) ServerPlayNetworking.send(player, payload);
		} catch (RuntimeException e) {
			Absorbaholic.LOGGER.error("Could not send {} to {}", payload.type().id(), player.getScoreboardName(), e);
		}
	}

	private static boolean canReceive(ServerPlayer player, CustomPacketPayload.Type<?> type) {
		return player.connection != null && ServerPlayNetworking.canSend(player, type);
	}
}
