package dev.absorbaholic.core;

/**
 * The per-absorption roll. One uniform {@code r} in [0, 1): {@code r < pureChance} → PURE; else
 * {@code r < pureChance + mutationChance} → a mutation, whose side is a second uniform {@code coin} in [0, 1)
 * ({@code coin < 0.5} → trait, else weakness); else NORMAL. Pure: the randomness is injected.
 */
public final class MutationRoll {
	/** What an absorption does to the levels of the source's trait and weakness (see {@link LevelStacking}). */
	public enum Outcome {
		/** trait +1, weakness +1 */
		NORMAL,
		/** trait +1, weakness +0 */
		PURE,
		/** trait +2, weakness +1 */
		MUTATE_TRAIT,
		/** trait +1, weakness +2 */
		MUTATE_WEAKNESS;

		public boolean isMutation() {
			return this == MUTATE_TRAIT || this == MUTATE_WEAKNESS;
		}

		/** Mutations and pure absorptions are announced loudly (chat broadcast, distinct sound and particles). */
		public boolean isSpecial() {
			return this != NORMAL;
		}
	}

	/** Source of uniform doubles in [0, 1). Adapters: RandomSource::nextDouble, java.util.Random::nextDouble. */
	@FunctionalInterface
	public interface Uniform {
		double next();
	}

	private MutationRoll() {}

	public static Outcome roll(double r, double coin, double pureChance, double mutationChance) {
		if (r < pureChance) return Outcome.PURE;
		if (r < pureChance + mutationChance) return coin < 0.5 ? Outcome.MUTATE_TRAIT : Outcome.MUTATE_WEAKNESS;
		return Outcome.NORMAL;
	}

	/** Rolls with the default chances of {@link AbsorbCaps}; draws the coin only for a mutation. */
	public static Outcome roll(Uniform random) {
		double r = random.next();
		if (r < AbsorbCaps.PURE_CHANCE || r >= AbsorbCaps.PURE_CHANCE + AbsorbCaps.MUTATION_CHANCE) {
			return roll(r, 0.0, AbsorbCaps.PURE_CHANCE, AbsorbCaps.MUTATION_CHANCE);
		}
		return roll(r, random.next(), AbsorbCaps.PURE_CHANCE, AbsorbCaps.MUTATION_CHANCE);
	}
}
