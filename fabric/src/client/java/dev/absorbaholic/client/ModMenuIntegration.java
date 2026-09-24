package dev.absorbaholic.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.absorbaholic.client.screen.SettingsScreen;

/**
 * Mod Menu entrypoint ("modmenu"). Nothing else references this class, so it only loads when Mod Menu is installed.
 * The config screen is {@link SettingsScreen} (notification toggles + "My traits", active only in a world).
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
