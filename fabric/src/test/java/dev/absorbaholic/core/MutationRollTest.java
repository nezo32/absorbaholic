package dev.absorbaholic.core;

import static dev.absorbaholic.core.MutationRoll.Outcome.MUTATE_TRAIT;
import static dev.absorbaholic.core.MutationRoll.Outcome.MUTATE_WEAKNESS;
import static dev.absorbaholic.core.MutationRoll.Outcome.NORMAL;
import static dev.absorbaholic.core.MutationRoll.Outcome.PURE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class MutationRollTest {
	@Test
	void boundaries() {
		assertEquals(PURE, MutationRoll.roll(0.0, 0.9, 0.02, 0.15));
		assertEquals(PURE, MutationRoll.roll(0.0199, 0.9, 0.02, 0.15));
		assertEquals(MUTATE_TRAIT, MutationRoll.roll(0.02, 0.0, 0.02, 0.15));
		assertEquals(MUTATE_WEAKNESS, MutationRoll.roll(0.1699, 0.5, 0.02, 0.15));
		assertEquals(NORMAL, MutationRoll.roll(0.17, 0.0, 0.02, 0.15));
		assertEquals(NORMAL, MutationRoll.roll(0.9999, 0.0, 0.02, 0.15));
	}

	@Test
	void injectedSequence() {
		double[] seq = {0.05, 0.7};
		int[] i = {0};
		assertEquals(MUTATE_WEAKNESS, MutationRoll.roll(() -> seq[i[0]++]));
		assertEquals(2, i[0]);
		int[] j = {0};
		assertEquals(NORMAL, MutationRoll.roll(() -> new double[] {0.5}[j[0]++]));
		assertEquals(1, j[0], "no coin drawn for a normal roll");
	}

	@Test
	void distributionMatchesChances() {
		Random random = new Random(42);
		Map<MutationRoll.Outcome, Integer> counts = new EnumMap<>(MutationRoll.Outcome.class);
		int n = 200_000;
		for (int k = 0; k < n; k++) counts.merge(MutationRoll.roll(random::nextDouble), 1, Integer::sum);
		assertEquals(0.02, counts.get(PURE) / (double) n, 0.003);
		assertEquals(0.075, counts.get(MUTATE_TRAIT) / (double) n, 0.004);
		assertEquals(0.075, counts.get(MUTATE_WEAKNESS) / (double) n, 0.004);
		assertEquals(0.83, counts.get(NORMAL) / (double) n, 0.005);
	}
}
