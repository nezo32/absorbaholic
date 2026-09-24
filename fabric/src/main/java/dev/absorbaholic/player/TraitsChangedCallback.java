package dev.absorbaholic.player;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired on the server thread after a player's {@link PlayerTraits} were replaced ({@code PlayerData.setTraits}).
 * Listeners: the trait engine (recompute), traits sync (payload to the owner), aura sync.
 */
@FunctionalInterface
public interface TraitsChangedCallback {
	Event<TraitsChangedCallback> EVENT = EventFactory.createArrayBacked(TraitsChangedCallback.class, listeners -> (player, before, after) -> {
		for (TraitsChangedCallback l : listeners) l.onTraitsChanged(player, before, after);
	});

	void onTraitsChanged(ServerPlayer player, PlayerTraits before, PlayerTraits after);
}
