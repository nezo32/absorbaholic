package dev.absorbaholic.world;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.mixin.MinecraftServerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/** ServerLifecycleEvents.SERVER_STARTING: apply the Create World choice before levels load or anyone joins. */
public final class WorldSettingsBootstrap {
	private WorldSettingsBootstrap() {}

	/** Called from Absorbaholic#onInitialize. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(WorldSettingsBootstrap::onServerStarting);
		WorldStateSync.register();
	}

	public static void onServerStarting(MinecraftServer server) {
		// 1. new world from the Create World screen: the button value rides on this world's storage access
		PendingWorldSettings access = (PendingWorldSettings) ((MinecraftServerAccessor) server).absorbaholic$getStorageSource();
		Boolean pending = access.absorbaholic$takePendingEnabled();
		if (pending != null) {
			AbsorbWorldSettings.setEnabled(server, pending);
			server.getDataStorage().scheduleSave(); // persist now: a crash before the first autosave must not lose the choice
			Absorbaholic.LOGGER.info("Absorbaholic Mode {} for new world", pending ? "ON" : "OFF");
			return;
		}
		// 2. existing world with settings.dat: authoritative. 3. none (dedicated server, other launcher): OFF, written once.
		if (server.getDataStorage().get(AbsorbWorldSettings.TYPE) == null) {
			AbsorbWorldSettings.setEnabled(server, false);
			server.getDataStorage().scheduleSave();
		}
		Absorbaholic.LOGGER.debug("Absorbaholic Mode loaded: {}", AbsorbWorldSettings.isEnabled(server));
	}
}
