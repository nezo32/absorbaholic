package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class LevelValueTest {
	@Test
	void scaledMultipliesByLevel() {
		LevelValue v = LevelValue.scaled(0.5);
		assertEquals(0.0, v.at(0));
		assertEquals(0.5, v.at(1));
		assertEquals(1.5, v.at(3));
		assertEquals(Integer.MAX_VALUE, v.definedLevels());
	}

	@Test
	void perLevelIndexesAndClampsToLast() {
		LevelValue v = LevelValue.perLevel(0.75, 0.5, 0.3);
		assertEquals(0.0, v.at(0));
		assertEquals(0.75, v.at(1));
		assertEquals(0.3, v.at(3));
		assertEquals(0.3, v.at(5));
		assertEquals(3, v.definedLevels());
		assertEquals(2, LevelValue.perLevel(1.6).atInt(1));
	}

	@Test
	void fromJson() {
		assertEquals(LevelValue.scaled(2), LevelValue.fromJson(JsonParser.parseString("2")).orElseThrow());
		assertEquals(LevelValue.perLevel(1, 2), LevelValue.fromJson(JsonParser.parseString("[1, 2]")).orElseThrow());
		assertTrue(LevelValue.fromJson(JsonParser.parseString("[]")).isEmpty());
		assertTrue(LevelValue.fromJson(JsonParser.parseString("[1, \"x\"]")).isEmpty());
		assertTrue(LevelValue.fromJson(JsonParser.parseString("\"1\"")).isEmpty());
		assertTrue(LevelValue.fromJson(null).isEmpty());
	}

	@Test
	void codecRoundTripAndCollect() {
		LevelValue.Collected<LevelValue> c = LevelValue.collect(() ->
				LevelValue.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[0.1, 0.2]")).getOrThrow());
		assertEquals(LevelValue.perLevel(0.1, 0.2), c.value());
		assertEquals(List.of(LevelValue.perLevel(0.1, 0.2)), c.levelValues());
		assertEquals(JsonParser.parseString("3.0"), LevelValue.CODEC.encodeStart(JsonOps.INSTANCE, LevelValue.scaled(3)).getOrThrow());
		assertTrue(LevelValue.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[]")).isError());
	}

	@Test
	void collectOutsideScopeRecordsNothing() {
		LevelValue.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("1")).getOrThrow();
		LevelValue.Collected<Integer> c = LevelValue.collect(() -> 7);
		assertEquals(List.of(), c.levelValues());
	}
}
