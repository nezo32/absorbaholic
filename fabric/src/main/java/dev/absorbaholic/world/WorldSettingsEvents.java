package dev.absorbaholic.world;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/** Server-thread events of {@link AbsorbWorldSettings}: a setting changed; a source was discovered for the first time. */
public final class WorldSettingsEvents {
	@FunctionalInterface
	public interface Changed {
		void onChanged(MinecraftServer server, AbsorbWorldSettings settings);
	}

	@FunctionalInterface
	public interface Discovered {
		void onDiscovered(MinecraftServer server, Identifier source);
	}

	public static final Event<Changed> CHANGED = EventFactory.createArrayBacked(Changed.class, listeners -> (server, settings) -> {
		for (Changed l : listeners) l.onChanged(server, settings);
	});

	public static final Event<Discovered> DISCOVERED = EventFactory.createArrayBacked(Discovered.class, listeners -> (server, source) -> {
		for (Discovered l : listeners) l.onDiscovered(server, source);
	});

	private WorldSettingsEvents() {}
}
