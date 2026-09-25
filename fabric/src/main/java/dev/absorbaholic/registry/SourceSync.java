package dev.absorbaholic.registry;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.net.SourcesSyncPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Sends {@link SourcesSyncPayload} (every {@link SourceSummary}) to a player on
 * {@code ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS}, i.e. on join and after every /reload (right before vanilla's
 * tag packet), only if the client can receive it. The payload is built once per registry set. Server thread only.
 */
public final class SourceSync {
	private static @Nullable SourceMatcher<SourceDefinition> cachedFor;
	private static @Nullable SourcesSyncPayload cached;

	private SourceSync() {}

	public static void register() {
		ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> send(player));
	}

	/** Sends the current sources to {@code player} if its client has the mod; true if sent. Never throws. */
	public static boolean send(ServerPlayer player) {
		try {
			if (!ServerPlayNetworking.canSend(player, SourcesSyncPayload.TYPE)) return false;
			ServerPlayNetworking.send(player, payload());
			return true;
		} catch (RuntimeException e) {
			Absorbaholic.LOGGER.error("Absorbaholic: sending sources to {} failed", player.getName().getString(), e);
			return false;
		}
	}

	/** The payload for the current {@link SourceRegistry} contents (cached until the registry is replaced). */
	public static SourcesSyncPayload payload() {
		SourceMatcher<SourceDefinition> matcher = SourceRegistry.matcher();
		SourcesSyncPayload payload = cached;
		if (cachedFor != matcher || payload == null) {
			payload = new SourcesSyncPayload(matcher.all().stream().map(SourceSummary::of).toList());
			cached = payload;
			cachedFor = matcher;
		}
		return payload;
	}
}
