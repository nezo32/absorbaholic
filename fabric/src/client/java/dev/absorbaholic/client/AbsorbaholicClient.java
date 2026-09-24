package dev.absorbaholic.client;

import dev.absorbaholic.client.aura.AuraParticles;
import dev.absorbaholic.client.hud.AbsorbHud;
import dev.absorbaholic.client.input.AbsorbInput;
import dev.absorbaholic.client.notify.NotifyCommand;
import dev.absorbaholic.client.notify.NotifyConfig;
import dev.absorbaholic.client.screen.TraitsKeybind;
import net.fabricmc.api.ClientModInitializer;

/** Client entrypoint. Each work package owns exactly one {@code register()} called here. */
public final class AbsorbaholicClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		NotifyConfig.load();            // WP-0
		ClientNetworking.register();    // WP-CLIENT: payload receivers → ClientState
		NotifyCommand.register();       // WP-CLIENT
		AbsorbInput.register();         // WP-CLIENT: use-key intercept, C2S
		AbsorbHud.register();           // WP-UI: hint + channel ring
		TraitsKeybind.register();       // WP-UI: K → traits screen
		AuraParticles.register();       // WP-UI
	}
}
