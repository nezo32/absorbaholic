package dev.absorbaholic.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu entrypoint ("modmenu"). Nothing else references this class, so it only loads when Mod Menu is installed.
 * WP-UI: return {@code SettingsScreen::new} (notification toggles + "My traits" button, active only in a world).
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> null; // TODO(WP-UI)
	}
}
