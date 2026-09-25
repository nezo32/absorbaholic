package dev.absorbaholic;

import dev.absorbaholic.absorb.AbsorbHandler;
import dev.absorbaholic.command.AbsorbCommands;
import dev.absorbaholic.net.AbsorbNetworking;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.trait.behavior.BuiltinBehaviors;
import dev.absorbaholic.world.WorldSettingsBootstrap;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint (runs on dedicated servers and clients). Each work package owns exactly one {@code register()}
 * called here; the order matters only where noted.
 */
public final class Absorbaholic implements ModInitializer {
	public static final String MOD_ID = "absorbaholic";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** {@code absorbaholic:<path>}. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		AbsorbNetworking.register();        // WP-0: payload types first (receivers need them)
		PlayerData.register();              // WP-0 attachments + WP-PLAYER lifecycle / traits sync
		BuiltinBehaviors.register();        // WP-BEH-*: behavior types before any source can resolve
		SourceRegistry.register();          // WP-REG: reload listener, tag cache, sources sync
		WorldSettingsBootstrap.register();  // WP-0 bootstrap + WP-PLAYER world state sync
		TraitEngine.register();             // WP-ENGINE: hooks, ticking, attributes, aura / movement sync
		AbsorbHandler.register();           // WP-ABSORB: channel, consume, roll, feedback
		AbsorbCommands.register();          // WP-CMD
	}
}
