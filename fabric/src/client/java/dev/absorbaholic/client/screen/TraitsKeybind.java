package dev.absorbaholic.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.absorbaholic.Absorbaholic;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The "Open traits" key ({@code key.absorbaholic.traits}, default K, category {@code key.category.absorbaholic.main}).
 * Pressed in game with no screen open, it opens {@link TraitsScreen} with the player's own traits. The default key is
 * {@link InputConstants#KEY_K} (its raw code differs between 26.2 and 26.3; javac inlines the right one per build).
 */
public final class TraitsKeybind {
	private static @Nullable KeyMapping openTraits;

	private TraitsKeybind() {}

	public static void register() {
		KeyMapping.Category category = KeyMapping.Category.register(Absorbaholic.id("main"));
		openTraits = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.absorbaholic.traits", InputConstants.KEY_K, category));
		ClientTickEvents.END_CLIENT_TICK.register(TraitsKeybind::tick);
	}

	/** The registered key mapping (null before {@link #register()}). */
	public static @Nullable KeyMapping key() {
		return openTraits;
	}

	private static void tick(Minecraft mc) {
		if (openTraits == null) return;
		while (openTraits.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(TraitsScreen.own(null));
		}
	}
}
