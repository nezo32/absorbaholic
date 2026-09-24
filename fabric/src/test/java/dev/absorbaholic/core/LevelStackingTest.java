package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.absorbaholic.core.LevelStacking.Levels;
import dev.absorbaholic.core.MutationRoll.Outcome;
import org.junit.jupiter.api.Test;

class LevelStackingTest {
	@Test
	void firstAbsorption() {
		assertEquals(new Levels(1, 1), LevelStacking.apply(Levels.NONE, 3, Outcome.NORMAL));
		assertEquals(new Levels(1, 0), LevelStacking.apply(Levels.NONE, 3, Outcome.PURE));
		assertEquals(new Levels(2, 1), LevelStacking.apply(Levels.NONE, 3, Outcome.MUTATE_TRAIT));
		assertEquals(new Levels(1, 2), LevelStacking.apply(Levels.NONE, 3, Outcome.MUTATE_WEAKNESS));
	}

	@Test
	void capsAtMaxLevel() {
		assertEquals(new Levels(3, 3), LevelStacking.apply(new Levels(2, 2), 3, Outcome.MUTATE_TRAIT));
		assertEquals(new Levels(3, 3), LevelStacking.apply(new Levels(2, 3), 3, Outcome.MUTATE_WEAKNESS));
		assertEquals(new Levels(1, 1), LevelStacking.apply(Levels.NONE, 1, Outcome.MUTATE_TRAIT));
	}

	@Test
	void refuseAtMax() {
		assertTrue(LevelStacking.canAbsorb(0, 3));
		assertTrue(LevelStacking.canAbsorb(2, 3));
		assertFalse(LevelStacking.canAbsorb(3, 3));
	}
}
