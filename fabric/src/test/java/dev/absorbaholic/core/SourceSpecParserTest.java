package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** WP-0 baseline; WP-REG extends it (and adds a test that parses every shipped source JSON). */
class SourceSpecParserTest {
	static final String SPEC_EXAMPLE = """
			{
			  "kind": "block",
			  "targets": ["minecraft:obsidian", "#minecraft:logs"],
			  "icon": "minecraft:obsidian",
			  "color": "#3B2754",
			  "tier": "common",
			  "max_level": 3,
			  "trait": {
			    "key": "blast_resistance",
			    "attributes": [
			      { "attribute": "minecraft:explosion_knockback_resistance", "operation": "add_value", "amount": [0.3, 0.6, 1.0] }
			    ],
			    "behaviors": [
			      { "type": "absorbaholic:damage_multiplier", "damage_tag": "minecraft:is_explosion", "multiplier": [0.75, 0.5, 0.3] }
			    ]
			  },
			  "weakness": {
			    "key": "heavy",
			    "attributes": [ { "attribute": "minecraft:movement_speed", "operation": "add_multiplied_total", "amount": [-0.1, -0.2, -0.3] } ]
			  }
			}""";

	static SourceSpecParser.Result parse(String json) {
		return SourceSpecParser.parse(JsonParser.parseString(json));
	}

	@Test
	void specExampleParses() {
		SourceSpec s = assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse(SPEC_EXAMPLE)).spec();
		assertEquals(SourceKind.BLOCK, s.kind());
		assertEquals(List.of("minecraft:obsidian", "#minecraft:logs"), s.targets());
		assertEquals(Optional.of("minecraft:obsidian"), s.icon());
		assertEquals(0x3B2754, s.color());
		assertEquals(3, s.maxLevel());
		assertEquals("blast_resistance", s.trait().key());
		assertEquals("absorbaholic:damage_multiplier", s.trait().behaviors().getFirst().type());
		assertTrue(s.trait().behaviors().getFirst().params().has("multiplier"));
		assertTrue(!s.trait().behaviors().getFirst().params().has("type"));
		assertEquals(LevelValue.perLevel(-0.1, -0.2, -0.3), s.weakness().attributes().getFirst().amount());
	}

	@Test
	void defaults() {
		SourceSpec s = assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse("""
				{"kind": "entity", "targets": ["zombie"], "trait": {"key": "a"}, "weakness": {"key": "b"}}""")).spec();
		assertEquals(AbsorbCaps.DEFAULT_MAX_LEVEL, s.maxLevel());
		assertEquals(Tier.COMMON, s.tier());
		assertEquals(List.of("minecraft:zombie"), s.targets());
	}

	@Test
	void disabled() {
		assertInstanceOf(SourceSpecParser.Result.Disabled.class, parse("{\"disabled\": true}"));
	}

	@Test
	void invalidCollectsAllErrors() {
		SourceSpecParser.Result.Invalid r = assertInstanceOf(SourceSpecParser.Result.Invalid.class, parse("""
				{"kind": "item", "targets": [], "max_level": 9, "color": "red",
				 "trait": {"key": "Bad Key", "attributes": [{"attribute": "minecraft:armor", "operation": "add", "amount": [1]}]},
				 "weakness": {"key": "w", "attributes": [{"attribute": "minecraft:armor", "operation": "add_value", "amount": [1, 2]}]}}"""));
		assertTrue(r.errors().size() >= 5, r.errors().toString());
	}

	@Test
	void shortArrayRejected() {
		SourceSpecParser.Result r = parse("""
				{"kind": "block", "targets": ["stone"], "max_level": 3, "trait": {"key": "a",
				 "attributes": [{"attribute": "armor", "operation": "add_value", "amount": [1, 2]}]}, "weakness": {"key": "b"}}""");
		assertInstanceOf(SourceSpecParser.Result.Invalid.class, r);
	}

	@Test
	void notAnObject() {
		assertInstanceOf(SourceSpecParser.Result.Invalid.class, parse("[]"));
	}
}
