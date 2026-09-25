package dev.absorbaholic.trait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

/**
 * The shared condition / entity-filter vocabulary (behaviors.md §0.2) decodes the designer's names and rejects typos,
 * without a game bootstrap (direct entity ids need the registry and are covered by the gametests).
 */
class ConditionAndFilterTest {
	/** Every name the catalog defines. */
	private static final Set<String> CATALOG = Set.of("always", "in_water", "underwater", "wet", "dry", "in_rain", "thundering", "in_sunlight",
			"in_darkness", "open_sky", "day", "night", "in_lava", "on_fire", "cold", "hot", "in_overworld", "in_nether", "in_end", "sneaking",
			"sprinting", "airborne", "low_health", "starving", "near_entity", "on_block");

	private static DataResult<Condition> condition(String json) {
		return Condition.FIELDS.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json));
	}

	@Test
	void predicateNamesMatchTheCatalog() {
		assertEquals(CATALOG, Condition.PREDICATES.keySet());
	}

	@Test
	void decodesSingleListNegatedAndAnyForms() {
		assertTrue(condition("{}").getOrThrow().isAlways());
		assertFalse(condition("{\"condition\": \"wet\"}").getOrThrow().isAlways());
		assertTrue(condition("{\"condition\": [\"dry\", \"in_sunlight\"]}").isSuccess());
		assertTrue(condition("{\"condition\": \"!in_water\"}").isSuccess());
		assertTrue(condition("{\"condition_any\": [\"hot\", \"wet\"]}").isSuccess());
		assertTrue(condition("{\"condition\": \"near_entity\", \"condition_entities\": [\"#minecraft:undead\"], \"condition_radius\": 8}").isSuccess());
		assertTrue(condition("{\"condition\": \"on_block\", \"condition_blocks\": \"#minecraft:snow\"}").isSuccess());
	}

	@Test
	void rejectsTyposAndBadRadius() {
		for (String bad : List.of("{\"condition\": \"sunny\"}", "{\"condition\": \"!wett\"}", "{\"condition_any\": [\"hot\", \"warm\"]}",
				"{\"condition\": \"near_entity\"}", "{\"condition\": \"on_block\"}",
				"{\"condition\": \"near_entity\", \"condition_entities\": [\"#minecraft:undead\"], \"condition_radius\": 17}",
				"{\"condition\": \"near_entity\", \"condition_entities\": [\"#minecraft:undead\"], \"condition_radius\": 0}")) {
			assertTrue(condition(bad).isError(), bad);
		}
	}

	@Test
	void targetFilterWordsAndTags() {
		assertTrue(TargetFilter.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("\"hostile\"")).isSuccess());
		assertTrue(TargetFilter.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[\"all_mobs\", \"players\", \"#absorbaholic:fears_golem\"]")).isSuccess());
		assertTrue(TargetFilter.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[\"#bad tag\"]")).isError());
		assertFalse(TargetFilter.HOSTILE.isAny());
		assertTrue(TargetFilter.ANY.isAny());
	}
}
