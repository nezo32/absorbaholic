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

	// --- WP-REG: reading files, edge cases ---

	static List<String> errors(SourceSpecParser.Result r) {
		return assertInstanceOf(SourceSpecParser.Result.Invalid.class, r).errors();
	}

	static boolean anyContains(List<String> errors, String part) {
		return errors.stream().anyMatch(e -> e.contains(part));
	}

	@Test
	void readTextParsesLikeParse() {
		assertEquals(parse(SPEC_EXAMPLE), SourceSpecParser.parseText(SPEC_EXAMPLE));
	}

	@Test
	void malformedJsonNeverThrows() {
		for (String bad : List.of("{", "{\"kind\": \"block\",}", "{kind: \"block\"}", "{'kind': 'block'}", "{} {}", "{\"a\": 1} trailing",
				"// comment\n{}", "\u0000", "[1, 2")) {
			List<String> errors = errors(SourceSpecParser.parseText(bad));
			assertEquals(1, errors.size(), bad + " → " + errors);
			assertTrue(errors.getFirst().startsWith("malformed JSON: "), bad + " → " + errors);
			assertTrue(!errors.getFirst().contains("\n"), "one line: " + errors);
		}
	}

	@Test
	void emptyFileIsInvalid() {
		assertEquals(List.of("empty file (or JSON null)"), errors(SourceSpecParser.parseText("")));
		assertEquals(List.of("empty file (or JSON null)"), errors(SourceSpecParser.parseText("  \n ")));
		assertEquals(List.of("empty file (or JSON null)"), errors(SourceSpecParser.parseText("null")));
		assertEquals(List.of("root must be a JSON object"), errors(SourceSpecParser.parseText("42")));
	}

	@Test
	void unknownKindAndTier() {
		List<String> errors = errors(parse("""
				{"kind": "item", "tier": "mythic", "targets": ["stone"], "trait": {"key": "a"}, "weakness": {"key": "b"}}"""));
		assertTrue(anyContains(errors, "unknown kind \"item\""), errors.toString());
		assertTrue(anyContains(errors, "unknown tier"), errors.toString());
	}

	@Test
	void disabledOverridesEverythingElse() {
		assertInstanceOf(SourceSpecParser.Result.Disabled.class, parse("{\"disabled\": true, \"kind\": \"nonsense\"}"));
		assertInstanceOf(SourceSpecParser.Result.Disabled.class, SourceSpecParser.parseText("{\"disabled\": true}"));
		assertEquals(List.of("\"disabled\" must be a boolean"), errors(parse("{\"disabled\": \"yes\"}")));
		// "disabled": false is an ordinary source
		assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse("""
				{"disabled": false, "kind": "block", "targets": ["stone"], "trait": {"key": "a"}, "weakness": {"key": "b"}}"""));
	}

	@Test
	void missingSidesAndKeys() {
		List<String> errors = errors(parse("{\"kind\": \"block\", \"targets\": [\"stone\"], \"trait\": {}}"));
		assertTrue(anyContains(errors, "missing \"key\""), errors.toString());
		assertTrue(anyContains(errors, "\"weakness\" must be an object"), errors.toString());
	}

	@Test
	void badTargetsAndIcon() {
		List<String> errors = errors(parse("""
				{"kind": "block", "targets": ["Stone", 5, "#", "ok:fine"], "icon": "Not An Id", "trait": {"key": "a"}, "weakness": {"key": "b"}}"""));
		assertEquals(3, errors.stream().filter(e -> e.startsWith("bad target")).count(), errors.toString());
		assertTrue(anyContains(errors, "bad icon"), errors.toString());
		assertTrue(anyContains(errors(parse("{\"kind\": \"block\", \"targets\": \"stone\", \"trait\": {\"key\": \"a\"}, \"weakness\": {\"key\": \"b\"}}")),
				"\"targets\" must be a non-empty array"));
	}

	@Test
	void shortArraysAgainstMaxLevel() {
		String template = """
				{"kind": "block", "targets": ["stone"], "max_level": %s, "trait": {"key": "a",
				 "attributes": [{"attribute": "armor", "operation": "add_value", "amount": %s}]}, "weakness": {"key": "b"}}""";
		assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse(template.formatted(2, "[1, 2]")));
		assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse(template.formatted(5, "0.5")));
		assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse(template.formatted(1, "[3]")));
		List<String> errors = errors(parse(template.formatted(5, "[1, 2, 3]")));
		assertTrue(anyContains(errors, "array has 3 entries, max_level is 5"), errors.toString());
		assertTrue(anyContains(errors(parse(template.formatted(3, "[]"))), "non-empty number array"));
		assertTrue(anyContains(errors(parse(template.formatted(3, "[1, \"2\", 3]"))), "non-empty number array"));
		assertTrue(anyContains(errors(parse(template.formatted(0, "1"))), "\"max_level\" must be an integer"));
		assertTrue(anyContains(errors(parse(template.formatted(2.5, "1"))), "\"max_level\" must be an integer"));
	}

	@Test
	void behaviorParamsKeepEverythingButType() {
		SourceSpec s = assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse("""
				{"kind": "entity", "targets": ["blaze"], "trait": {"key": "a", "behaviors": [
				  {"type": "damage_multiplier", "multiplier": 0.5, "nested": {"x": [1, 2]}}]}, "weakness": {"key": "b"}}""")).spec();
		SourceSpec.BehaviorSpec b = s.trait().behaviors().getFirst();
		assertEquals("minecraft:damage_multiplier", b.type(), "type ids are normalized like any id");
		assertEquals(2, b.params().size());
		assertTrue(b.params().has("nested"));
		List<String> errors = errors(parse("""
				{"kind": "entity", "targets": ["blaze"], "trait": {"key": "a", "behaviors": [{"multiplier": 1}, 7, {"type": "Bad Type"}]},
				 "weakness": {"key": "b", "behaviors": {}}}"""));
		assertTrue(anyContains(errors, "missing \"type\""), errors.toString());
		assertTrue(anyContains(errors, "trait.behaviors[1] must be an object"), errors.toString());
		assertTrue(anyContains(errors, "bad behavior type id"), errors.toString());
		assertTrue(anyContains(errors, "weakness.behaviors must be an array"), errors.toString());
	}

	@Test
	void nameIsOptionalTranslationKey() {
		String template = """
				{"kind": "block", "targets": ["#minecraft:logs"], %s "trait": {"key": "a"}, "weakness": {"key": "b"}}""";
		SourceSpec named = assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse(template.formatted("\"name\": \"absorbaholic.source.wood\","))).spec();
		assertEquals(Optional.of("absorbaholic.source.wood"), named.name());
		SourceSpec unnamed = assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse(template.formatted(""))).spec();
		assertEquals(Optional.empty(), unnamed.name());
		for (String bad : List.of("\"name\": 5,", "\"name\": \"\",", "\"name\": \"two words\",", "\"name\": {\"text\": \"x\"},", "\"name\": null,")) {
			List<String> errors = errors(parse(template.formatted(bad)));
			assertTrue(anyContains(errors, "\"name\" must be a translation key string"), bad + " → " + errors);
		}
	}

	@Test
	void specExampleHasNoName() {
		SourceSpec s = assertInstanceOf(SourceSpecParser.Result.Parsed.class, parse(SPEC_EXAMPLE)).spec();
		assertEquals(Optional.empty(), s.name());
	}
}
