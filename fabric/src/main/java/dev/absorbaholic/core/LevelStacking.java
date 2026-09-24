package dev.absorbaholic.core;

/**
 * Pure level math of one source entry. A source not yet absorbed has trait level 0 and weakness level 0.
 * An absorption is refused (nothing consumed) while the trait is already at the source's max level.
 */
public final class LevelStacking {
	/** Trait and weakness level of one entry; weakness 0 = the weakness is inactive (pure entry). */
	public record Levels(int trait, int weakness) {
		public static final Levels NONE = new Levels(0, 0);
	}

	private LevelStacking() {}

	/** True iff another absorption of this source can raise its trait. */
	public static boolean canAbsorb(int traitLevel, int maxLevel) {
		return traitLevel < maxLevel;
	}

	/** Levels after one absorption with {@code outcome}; both capped at {@code maxLevel}. */
	public static Levels apply(Levels current, int maxLevel, MutationRoll.Outcome outcome) {
		int dt = switch (outcome) {
			case NORMAL, PURE, MUTATE_WEAKNESS -> 1;
			case MUTATE_TRAIT -> 2;
		};
		int dw = switch (outcome) {
			case PURE -> 0;
			case NORMAL, MUTATE_TRAIT -> 1;
			case MUTATE_WEAKNESS -> 2;
		};
		return new Levels(Math.min(maxLevel, current.trait() + dt), Math.min(maxLevel, current.weakness() + dw));
	}
}
