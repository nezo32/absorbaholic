package dev.absorbaholic.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.client.ClientNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The "Open traits" key ({@code key.absorbaholic.traits}, default K, category {@code key.category.absorbaholic.main}).
 * Pressed in game with no screen open, it opens {@link TraitsScreen} with the player's own traits. The default key is
 * resolved at runtime by name ({@code key.keyboard.k}): its raw code and input type differ between 26.2 (GLFW keysym
 * 75) and 26.3 (SDL scancode 14), and {@code InputConstants.KEY_K} is a compile-time constant that javac would inline
 * from whichever version the jar was built against.
 * Also installs {@link TraitsScreen#open} as the opener for {@code /absorbaholic traits} answers.
 */
public final class TraitsKeybind {
	/** The runtime name of the default key. */
	public static final String DEFAULT_KEY_NAME = "key.keyboard.k";

	private static @Nullable KeyMapping openTraits;

	private TraitsKeybind() {}

	public static void register() {
		KeyMapping.Category category = KeyMapping.Category.register(Absorbaholic.id("main"));
		InputConstants.Key k = defaultKey();
		openTraits = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.absorbaholic.traits", k.getType(), k.getValue(), category));
		ClientTickEvents.END_CLIENT_TICK.register(TraitsKeybind::tick);
		ClientNetworking.setTraitsScreenOpener(TraitsScreen::open);
	}

	/** The default key, K, as the running game names it. */
	public static InputConstants.Key defaultKey() {
		return InputConstants.getKey(DEFAULT_KEY_NAME);
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
