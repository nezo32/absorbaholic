package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Review M5 / m7: a shipped {@code add_value} attribute entry must do what its level says on a plain player. An amount
 * that the clamp (or vanilla's own attribute range, which is the same for these) cuts off, e.g. a negative
 * knockback_resistance on a base of 0 or an oxygen_bonus above 8, silently turns into a no-op or a smaller value.
 * attack_damage is not checked: the held weapon adds to its final value, so a negative amount works with a weapon.
 */
class ShippedAttributeRangeTest {
	private static final Path DATA = Path.of("src/main/resources/data");

	/** Player base values of the clamped attributes (vanilla defaults of 26.2 / 26.3). */
	private static final Map<String, Double> PLAYER_BASE = Map.ofEntries(
			Map.entry("minecraft:max_health", 20.0), Map.entry("minecraft:scale", 1.0),
			Map.entry("minecraft:block_interaction_range", 4.5), Map.entry("minecraft:entity_interaction_range", 3.0),
			Map.entry("minecraft:jump_strength", 0.42), Map.entry("minecraft:step_height", 0.6),
			Map.entry("minecraft:armor", 0.0), Map.entry("minecraft:armor_toughness", 0.0),
			Map.entry("minecraft:attack_knockback", 0.0),
			Map.entry("minecraft:knockback_resistance", 0.0), Map.entry("minecraft:gravity", 0.08),
			Map.entry("minecraft:safe_fall_distance", 3.0), Map.entry("minecraft:fall_damage_multiplier", 1.0),
			Map.entry("minecraft:luck", 0.0), Map.entry("minecraft:oxygen_bonus", 0.0), Map.entry("minecraft:burning_time", 1.0),
			Map.entry("minecraft:water_movement_efficiency", 0.0), Map.entry("minecraft:movement_efficiency", 0.0),
			Map.entry("minecraft:mining_efficiency", 0.0), Map.entry("minecraft:submerged_mining_speed", 0.2),
			Map.entry("minecraft:sneaking_speed", 0.3), Map.entry("minecraft:explosion_knockback_resistance", 0.0),
			Map.entry("minecraft:max_absorption", 0.0));

	@Test
	void addValueAmountsStayInsideTheirClampOnAPlainPlayer() throws IOException {
		if (!Files.isDirectory(DATA)) return;
		List<String> problems = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(DATA)) {
			for (Path file : walk.filter(p -> p.toString().replace('\\', '/').contains("/absorbaholic/source/") && p.toString().endsWith(".json")).toList()) {
				JsonObject source = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				for (String side : List.of("trait", "weakness")) {
					JsonObject s = source.getAsJsonObject(side);
					if (s == null || !s.has("attributes")) continue;
					for (JsonElement e : s.getAsJsonArray("attributes")) {
						JsonObject a = e.getAsJsonObject();
						String attribute = a.get("attribute").getAsString();
						if (!"add_value".equals(a.get("operation").getAsString())) continue;
						Optional<AbsorbCaps.Clamp> clamp = AbsorbCaps.clampFor(attribute);
						Double base = PLAYER_BASE.get(attribute);
						if (clamp.isEmpty() || base == null) continue;
						double lo = clamp.get().lower(base);
						double hi = clamp.get().upper(base);
						JsonElement amount = a.get("amount");
						List<JsonElement> levels = amount.isJsonArray() ? amount.getAsJsonArray().asList() : List.of(amount);
						for (int i = 0; i < levels.size(); i++) {
							double v = base + levels.get(i).getAsDouble();
							if (v < lo - 1e-9 || v > hi + 1e-9) {
								problems.add(DATA.relativize(file) + " " + side + " " + attribute + " level " + (i + 1) + ": " + base + " + "
										+ levels.get(i) + " is outside " + lo + ".." + hi);
							}
						}
					}
				}
			}
		}
		assertTrue(problems.isEmpty(), "shipped attribute amounts cut off by their clamp:\n" + String.join("\n", problems));
	}
}
