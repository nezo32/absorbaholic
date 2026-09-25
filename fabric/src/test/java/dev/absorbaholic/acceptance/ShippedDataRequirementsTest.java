package dev.absorbaholic.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.absorbaholic.core.AbsorbCaps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (tester): the shipped source data against the product brief. At least 60 sources (30+ blocks and 30+
 * mobs), every source exactly one trait and one weakness that each do something, the brief's named examples exist
 * with traits / weaknesses of the intended kind, and the unabsorbable tags cover air, water, portals and command blocks.
 */
class ShippedDataRequirementsTest {
	private static final Path DATA = Path.of("src/main/resources/data");
	private static final Map<String, JsonObject> SOURCES = new TreeMap<>();

	@BeforeAll
	static void load() throws IOException {
		assertTrue(Files.isDirectory(DATA), "run from the fabric project dir");
		try (Stream<Path> walk = Files.walk(DATA)) {
			for (Path file : walk.filter(p -> p.toString().replace('\\', '/').contains("/absorbaholic/source/")
					&& p.toString().endsWith(".json")).toList()) {
				String rel = DATA.relativize(file).toString().replace('\\', '/'); // <ns>/absorbaholic/source/<path>.json
				String ns = rel.substring(0, rel.indexOf('/'));
				String path = rel.substring(rel.indexOf("/source/") + "/source/".length(), rel.length() - ".json".length());
				JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				if (o.has("disabled")) continue;
				SOURCES.put(ns + ":" + path, o);
			}
		}
	}

	private static long count(String kind) {
		return SOURCES.values().stream().filter(o -> kind.equals(o.get("kind").getAsString())).count();
	}

	@Test
	void atLeastSixtySourcesThirtyBlocksThirtyMobs() {
		assertTrue(SOURCES.size() >= 60, "sources: " + SOURCES.size());
		assertTrue(count("block") >= 30, "block sources: " + count("block"));
		assertTrue(count("entity") >= 30, "mob sources: " + count("entity"));
	}

	private static boolean doesSomething(JsonObject side) {
		JsonArray a = side.has("attributes") ? side.getAsJsonArray("attributes") : new JsonArray();
		JsonArray b = side.has("behaviors") ? side.getAsJsonArray("behaviors") : new JsonArray();
		return !a.isEmpty() || !b.isEmpty();
	}

	@Test
	void everySourceHasExactlyOneTraitAndOneWeaknessThatDoSomething() {
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, JsonObject> e : SOURCES.entrySet()) {
			JsonObject o = e.getValue();
			for (String side : new String[] {"trait", "weakness"}) {
				JsonElement el = o.get(side);
				if (el == null || !el.isJsonObject()) {
					problems.add(e.getKey() + ": no " + side + " object");
					continue;
				}
				JsonObject s = el.getAsJsonObject();
				if (!s.has("key") || s.get("key").getAsString().isBlank()) problems.add(e.getKey() + ": " + side + " without key");
				if (!doesSomething(s)) problems.add(e.getKey() + ": " + side + " has neither attributes nor behaviors");
			}
			for (String plural : new String[] {"traits", "weaknesses"}) {
				if (o.has(plural)) problems.add(e.getKey() + ": has a '" + plural + "' list (must be exactly one)");
			}
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems));
	}

	@Test
	void maxLevelIsThreeByDefault() {
		assertEquals(3, AbsorbCaps.DEFAULT_MAX_LEVEL);
		List<String> other = new ArrayList<>();
		for (Map.Entry<String, JsonObject> e : SOURCES.entrySet()) {
			int max = e.getValue().has("max_level") ? e.getValue().get("max_level").getAsInt() : AbsorbCaps.DEFAULT_MAX_LEVEL;
			assertTrue(max >= AbsorbCaps.MIN_MAX_LEVEL && max <= AbsorbCaps.MAX_MAX_LEVEL, e.getKey() + " max_level " + max);
			if (max != 3) other.add(e.getKey() + "=" + max);
		}
		// the dragon egg is deliberately a one-level legendary (flight; design/sources.md); anything else is a regression
		other.remove("absorbaholic:dragon_egg=1");
		assertTrue(other.isEmpty(), "every shipped source is max III (brief: max III default); deviating: " + other);
	}

	/** Behavior type paths and attribute paths of one side of a source. */
	private static Set<String> effects(String id, String side) {
		JsonObject o = SOURCES.get(id);
		assertNotNull(o, "missing shipped source " + id);
		JsonObject s = o.getAsJsonObject(side);
		Set<String> out = new HashSet<>();
		if (s.has("behaviors")) {
			for (JsonElement b : s.getAsJsonArray("behaviors")) {
				String type = b.getAsJsonObject().get("type").getAsString();
				out.add(type.substring(type.indexOf(':') + 1));
			}
		}
		if (s.has("attributes")) {
			for (JsonElement a : s.getAsJsonArray("attributes")) {
				String attr = a.getAsJsonObject().get("attribute").getAsString();
				out.add(attr.substring(attr.indexOf(':') + 1));
			}
		}
		return out;
	}

	private static Set<String> targets(String id) {
		Set<String> out = new HashSet<>();
		for (JsonElement t : SOURCES.get(id).getAsJsonArray("targets")) out.add(t.getAsString());
		return out;
	}

	private record Example(String source, String kind, String target, String traitNeeds, String weaknessNeeds) {}

	/**
	 * The brief's examples: the source that owns the vanilla target, and one behavior type / attribute the trait and
	 * the weakness must use to express the brief's intent ("a|b" = either).
	 */
	private static final List<Example> EXAMPLES = List.of(
			new Example("absorbaholic:obsidian", "block", "minecraft:obsidian", "explosion_knockback_resistance|damage_multiplier", "movement_speed"),
			new Example("absorbaholic:lava", "block", "minecraft:lava", "damage_multiplier", "environment_damage"),
			new Example("absorbaholic:slime_block", "block", "minecraft:slime_block", "bounciness|jump_strength", "knockback_multiplier"),
			new Example("absorbaholic:ice", "block", "#minecraft:ice", "walk_on_fluid", "burning_time|damage_multiplier"),
			new Example("absorbaholic:cactus", "block", "minecraft:cactus", "retaliate", "heal_multiplier|hunger_drain"),
			new Example("absorbaholic:sponge", "block", "minecraft:sponge", "oxygen_bonus", "hunger_drain|environment_damage"),
			new Example("absorbaholic:glow_blocks", "block", "minecraft:glowstone", "status_effect", "detection_range"),
			new Example("absorbaholic:magma_block", "block", "minecraft:magma_block", "damage_multiplier|aura", "environment_damage|struck_by"),
			new Example("absorbaholic:bedrock", "block", "minecraft:bedrock", "knockback_resistance|armor", "jump_strength"),
			new Example("absorbaholic:ancient_debris", "block", "minecraft:ancient_debris", "damage_multiplier", "hunger_drain"),
			new Example("absorbaholic:dragon_egg", "block", "minecraft:dragon_egg", "flight", "wipe_on_death"),
			new Example("absorbaholic:enderman", "entity", "minecraft:enderman", "teleport", "environment_damage"),
			new Example("absorbaholic:bee", "entity", "minecraft:bee", "air_jump|flight|glide", "hunger_drain|environment_damage"),
			new Example("absorbaholic:spider", "entity", "minecraft:spider", "climb_walls", "effect_modifier"),
			new Example("absorbaholic:creeper", "entity", "minecraft:creeper", "sneak_detonate", "status_effect|mob_attitude"),
			new Example("absorbaholic:phantom", "entity", "minecraft:phantom", "glide|flight", "environment_damage"),
			new Example("absorbaholic:blaze", "entity", "minecraft:blaze", "shoot_projectile", "environment_damage|struck_by"),
			new Example("absorbaholic:iron_golem", "entity", "minecraft:iron_golem", "attack_damage", "movement_speed|sink_in_water"),
			new Example("absorbaholic:chicken", "entity", "minecraft:chicken", "fall_damage_multiplier|safe_fall_distance", "mob_attitude"),
			new Example("absorbaholic:warden", "entity", "minecraft:warden", "sonic_boom", "status_effect"),
			new Example("absorbaholic:wither", "entity", "minecraft:wither", "attack_effect", "max_health|effect_modifier"));

	@Test
	void briefExamplesExistWithMatchingTraitsAndWeaknesses() {
		List<String> problems = new ArrayList<>();
		for (Example ex : EXAMPLES) {
			JsonObject o = SOURCES.get(ex.source());
			if (o == null) {
				problems.add(ex.source() + ": missing");
				continue;
			}
			if (!ex.kind().equals(o.get("kind").getAsString())) problems.add(ex.source() + ": kind " + o.get("kind"));
			if (!targets(ex.source()).contains(ex.target())) problems.add(ex.source() + ": does not target " + ex.target());
			Set<String> t = effects(ex.source(), "trait");
			Set<String> w = effects(ex.source(), "weakness");
			if (Stream.of(ex.traitNeeds().split("\\|")).noneMatch(t::contains)) problems.add(ex.source() + ": trait " + t + " lacks " + ex.traitNeeds());
			if (Stream.of(ex.weaknessNeeds().split("\\|")).noneMatch(w::contains)) problems.add(ex.source() + ": weakness " + w + " lacks " + ex.weaknessNeeds());
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems));
	}

	/** A vanilla target is claimed directly by at most one shipped source (the matcher would WARN and pick one). */
	@Test
	void noTwoSourcesClaimTheSameDirectTarget() {
		Map<String, List<String>> owners = new LinkedHashMap<>();
		for (Map.Entry<String, JsonObject> e : SOURCES.entrySet()) {
			String kind = e.getValue().get("kind").getAsString();
			for (String t : targets(e.getKey())) {
				if (!t.startsWith("#")) owners.computeIfAbsent(kind + " " + t, k -> new ArrayList<>()).add(e.getKey());
			}
		}
		List<String> dup = owners.entrySet().stream().filter(e -> e.getValue().size() > 1).map(e -> e.getKey() + " " + e.getValue()).toList();
		assertTrue(dup.isEmpty(), "direct targets claimed twice:\n" + String.join("\n", dup));
	}

	private static Set<String> tag(String registry, String name) throws IOException {
		JsonObject o = JsonParser.parseString(Files.readString(DATA.resolve("absorbaholic/tags/" + registry + "/" + name + ".json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		Set<String> out = new HashSet<>();
		for (JsonElement v : o.getAsJsonArray("values")) out.add(v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString());
		return out;
	}

	@Test
	void unabsorbableTagsCoverTheBriefsList() throws IOException {
		Set<String> blocks = tag("block", "unabsorbable");
		for (String required : List.of("#minecraft:air", "minecraft:water", "minecraft:nether_portal", "minecraft:end_portal",
				"minecraft:end_gateway", "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block")) {
			assertTrue(blocks.contains(required), "block tag absorbaholic:unabsorbable lacks " + required);
		}
		assertTrue(tag("entity_type", "unabsorbable").contains("minecraft:player"), "players are never absorbable");
	}

	@Test
	void noSourceTargetsAnUnabsorbableBlockDirectly() throws IOException {
		Set<String> blocks = tag("block", "unabsorbable");
		List<String> bad = new ArrayList<>();
		for (Map.Entry<String, JsonObject> e : SOURCES.entrySet()) {
			if (!"block".equals(e.getValue().get("kind").getAsString())) continue;
			for (String t : targets(e.getKey())) {
				if (blocks.contains(t) || t.equals("minecraft:air") || t.equals("minecraft:cave_air") || t.equals("minecraft:void_air")) {
					bad.add(e.getKey() + " -> " + t);
				}
			}
		}
		assertTrue(bad.isEmpty(), "sources targeting unabsorbable blocks (dead data):\n" + String.join("\n", bad));
	}

	@Test
	void everySourceHasAColorAndATier() {
		List<String> bad = new ArrayList<>();
		Set<String> tiers = Set.of("common", "uncommon", "rare", "epic", "legendary");
		for (Map.Entry<String, JsonObject> e : SOURCES.entrySet()) {
			JsonObject o = e.getValue();
			if (!o.has("color") || !o.get("color").getAsString().matches("#[0-9A-Fa-f]{6}")) bad.add(e.getKey() + ": color");
			if (o.has("tier") && !tiers.contains(o.get("tier").getAsString())) bad.add(e.getKey() + ": tier " + o.get("tier"));
		}
		assertTrue(bad.isEmpty(), String.join("\n", bad));
		assertFalse(SOURCES.isEmpty());
	}
}
