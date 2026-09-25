package dev.absorbaholic.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.SplittableRandom;

import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelStacking;
import dev.absorbaholic.core.MutationRoll;
import dev.absorbaholic.core.MutationRoll.Outcome;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (tester): the gamble of the product brief, measured on the real {@link MutationRoll#roll(MutationRoll.Uniform)}
 * (the exact call the server makes with {@code player.getRandom()::nextDouble}) over one million rolls per seed:
 * 15 % mutation split 50/50 between trait (+2) and weakness (+2), 2 % pure (no weakness), the rest normal.
 */
class MutationRollDistributionTest {
	private static final int N = 1_000_000;
	/** Tolerance in standard deviations of a binomial proportion (5 sigma: a false failure is ~1 in 1.7 million). */
	private static final double SIGMAS = 5.0;

	private static Map<Outcome, Integer> roll(long seed) {
		SplittableRandom random = new SplittableRandom(seed);
		Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
		for (Outcome o : Outcome.values()) counts.put(o, 0);
		for (int i = 0; i < N; i++) counts.merge(MutationRoll.roll(random::nextDouble), 1, Integer::sum);
		return counts;
	}

	private static void assertRate(String what, int count, double expected) {
		double observed = count / (double) N;
		double sigma = Math.sqrt(expected * (1.0 - expected) / N);
		assertTrue(Math.abs(observed - expected) <= SIGMAS * sigma,
				what + ": expected " + expected + " ± " + (SIGMAS * sigma) + ", observed " + observed + " (" + count + "/" + N + ")");
	}

	@Test
	void defaultChancesAreTheBriefs() {
		assertEquals(0.15, AbsorbCaps.MUTATION_CHANCE, 1e-12, "15 % mutation");
		assertEquals(0.02, AbsorbCaps.PURE_CHANCE, 1e-12, "2 % pure");
	}

	@Test
	void oneMillionRollsMatchTheBrief() {
		for (long seed : new long[] {1L, 0xABCDEF01L, 20260924L}) {
			Map<Outcome, Integer> c = roll(seed);
			int mutations = c.get(Outcome.MUTATE_TRAIT) + c.get(Outcome.MUTATE_WEAKNESS);
			assertRate("seed " + seed + " mutation (any side)", mutations, 0.15);
			assertRate("seed " + seed + " mutation, trait doubled", c.get(Outcome.MUTATE_TRAIT), 0.075);
			assertRate("seed " + seed + " mutation, weakness doubled", c.get(Outcome.MUTATE_WEAKNESS), 0.075);
			assertRate("seed " + seed + " pure", c.get(Outcome.PURE), 0.02);
			assertRate("seed " + seed + " normal", c.get(Outcome.NORMAL), 0.83);

			// 50/50 within mutations
			double traitShare = c.get(Outcome.MUTATE_TRAIT) / (double) mutations;
			double sigma = Math.sqrt(0.25 / mutations);
			assertTrue(Math.abs(traitShare - 0.5) <= SIGMAS * sigma, "seed " + seed + " trait share of mutations " + traitShare);

			// Pearson chi-square over the 4 outcomes, df = 3: critical value 16.27 at p = 0.001
			double[] expected = {0.83, 0.02, 0.075, 0.075};
			double chi2 = 0;
			Outcome[] order = {Outcome.NORMAL, Outcome.PURE, Outcome.MUTATE_TRAIT, Outcome.MUTATE_WEAKNESS};
			for (int i = 0; i < order.length; i++) {
				double e = expected[i] * N;
				double d = c.get(order[i]) - e;
				chi2 += d * d / e;
			}
			assertTrue(chi2 < 16.27, "seed " + seed + " chi-square " + chi2 + " >= 16.27 (df 3, p 0.001): " + c);
		}
	}

	/** What each outcome does to a first absorption, as the brief words it: doubled = +2, pure = no weakness. */
	@Test
	void outcomesDoWhatTheBriefSays() {
		LevelStacking.Levels none = LevelStacking.Levels.NONE;
		assertEquals(new LevelStacking.Levels(1, 1), LevelStacking.apply(none, 3, Outcome.NORMAL));
		assertEquals(2, LevelStacking.apply(none, 3, Outcome.MUTATE_TRAIT).trait(), "trait doubled: +2");
		assertEquals(2, LevelStacking.apply(none, 3, Outcome.MUTATE_WEAKNESS).weakness(), "weakness doubled: +2");
		assertEquals(0, LevelStacking.apply(none, 3, Outcome.PURE).weakness(), "pure: no weakness");
		assertEquals(1, LevelStacking.apply(none, 3, Outcome.PURE).trait(), "pure still grants the trait");
	}

	/** Re-absorbing raises to II and III (normal rolls) and never past the default max III. */
	@Test
	void reabsorbRaisesToTwoAndThreeThenStops() {
		LevelStacking.Levels l = LevelStacking.Levels.NONE;
		int max = AbsorbCaps.DEFAULT_MAX_LEVEL;
		assertEquals(3, max, "max level III by default");
		l = LevelStacking.apply(l, max, Outcome.NORMAL);
		assertEquals(new LevelStacking.Levels(1, 1), l);
		l = LevelStacking.apply(l, max, Outcome.NORMAL);
		assertEquals(new LevelStacking.Levels(2, 2), l);
		assertTrue(LevelStacking.canAbsorb(l.trait(), max));
		l = LevelStacking.apply(l, max, Outcome.NORMAL);
		assertEquals(new LevelStacking.Levels(3, 3), l);
		assertTrue(!LevelStacking.canAbsorb(l.trait(), max), "refused at III");
		// every outcome sequence stays within [0, max]
		SplittableRandom random = new SplittableRandom(7);
		for (int run = 0; run < 10_000; run++) {
			LevelStacking.Levels x = LevelStacking.Levels.NONE;
			int steps = 0;
			while (LevelStacking.canAbsorb(x.trait(), max)) {
				x = LevelStacking.apply(x, max, MutationRoll.roll(random::nextDouble));
				steps++;
				assertTrue(x.trait() >= 1 && x.trait() <= max && x.weakness() >= 0 && x.weakness() <= max, "levels in range: " + x);
			}
			assertTrue(steps >= 2 && steps <= 3, "a max-III source takes 2 (one trait mutation) or 3 absorptions, took " + steps);
		}
	}
}
