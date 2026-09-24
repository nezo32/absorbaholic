package dev.absorbaholic.text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.absorbaholic.Absorbaholic;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Translatable components for text the SERVER sends (command feedback, chat announcements, vanilla-client fallback
 * titles, trait names inside those). Each carries our own en_us value as its fallback, read once from the mod's
 * {@code assets/absorbaholic/lang/en_us.json} on the classpath, so vanilla clients (no mod lang) see English instead of
 * raw keys and no English text is duplicated in Java. Unknown keys (e.g. a datapack's new trait key) fall back to
 * the key itself. Client-only code uses plain {@code Component.translatable}.
 */
public final class Texts {
	private static final String EN_US = "/assets/absorbaholic/lang/en_us.json";
	private static volatile Map<String, String> english;

	private Texts() {}

	/** {@code Component.translatableWithFallback(key, <en_us value>, args)}. */
	public static MutableComponent tr(String key, Object... args) {
		return Component.translatableWithFallback(key, english().get(key), args);
	}

	/** The en_us value of {@code key}, or null. */
	public static String englishOf(String key) {
		return english().get(key);
	}

	private static Map<String, String> english() {
		Map<String, String> map = english;
		if (map == null) {
			map = load();
			english = map;
		}
		return map;
	}

	private static Map<String, String> load() {
		Map<String, String> map = new HashMap<>();
		try (InputStream in = Texts.class.getResourceAsStream(EN_US)) {
			if (in == null) {
				Absorbaholic.LOGGER.warn("{} not found; server-side messages have no English fallback", EN_US);
				return Map.of();
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
					if (e.getValue().isJsonPrimitive()) map.put(e.getKey(), e.getValue().getAsString());
				}
			}
		} catch (Exception e) {
			Absorbaholic.LOGGER.warn("Could not read {}", EN_US, e);
		}
		return Map.copyOf(map);
	}
}
