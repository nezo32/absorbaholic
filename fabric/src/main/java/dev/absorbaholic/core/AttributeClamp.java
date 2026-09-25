package dev.absorbaholic.core;

/**
 * Pure attribute math. Vanilla computes an attribute's final value as
 * {@code (base + Σadd_value) × (1 + Σadd_multiplied_base) × Π(1 + add_multiplied_total)}. The engine sums our
 * modifiers per operation, compares the final value with and without them and, when a {@link AbsorbCaps.Clamp} is
 * violated, adds one correcting {@code add_multiplied_total} modifier.
 */
public final class AttributeClamp {
	private AttributeClamp() {}

	/** Vanilla's formula (before the attribute's own sanitizing range). */
	public static double finalValue(double base, double sumAdd, double sumMultipliedBase, double productMultipliedTotal) {
		return (base + sumAdd) * (1.0 + sumMultipliedBase) * productMultipliedTotal;
	}

	/**
	 * The final value we allow: {@code withOurs} clamped to [lower, upper], but never pushed beyond {@code without}
	 * (if other sources already put the value outside the range, our modifiers may only move it back toward it or
	 * leave it).
	 */
	public static double target(double without, double withOurs, double lower, double upper) {
		double lo = Math.min(lower, without);
		double hi = Math.max(upper, without);
		return Math.clamp(withOurs, lo, hi);
	}

	/**
	 * Amount of the correcting {@code add_multiplied_total} modifier that turns {@code withOurs} into {@code target};
	 * 0 when no correction is needed or possible ({@code withOurs} is 0).
	 */
	public static double correction(double withOurs, double target) {
		if (withOurs == target || withOurs == 0.0 || Double.isNaN(withOurs) || Double.isNaN(target)) return 0.0;
		return target / withOurs - 1.0;
	}
}
