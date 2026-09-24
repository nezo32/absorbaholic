package dev.absorbaholic.core;

/**
 * Pure combination rules for the multiplicative hooks of behaviors (incoming / outgoing damage, healing, exhaustion,
 * visibility). The engine multiplies the factors of all active entries and applies the caps of {@link AbsorbCaps}.
 */
public final class FactorMath {
	/** Incoming damage split into the part that is always dealt and the weakness extra that must pass the gate. */
	public record Incoming(float base, float extra) {}

	private FactorMath() {}

	/**
	 * {@code traitProduct}: product of the trait entries' factors (reductions, &lt;= 1 expected); floored at
	 * {@link AbsorbCaps#DAMAGE_TAKEN_FLOOR}. {@code weaknessProduct}: product of the weakness entries' factors
	 * (amplifications, &gt;= 1 expected); capped at {@link AbsorbCaps#DAMAGE_TAKEN_WEAKNESS_CEILING}. A weakness product
	 * below 1 is treated as 1 and a trait product above 1 as 1: traits never amplify, weaknesses never protect.
	 */
	public static Incoming incoming(float amount, float traitProduct, float weaknessProduct) {
		if (!(amount > 0.0F)) return new Incoming(Math.max(0.0F, amount), 0.0F);
		float reduction = clamp(traitProduct, AbsorbCaps.DAMAGE_TAKEN_FLOOR, 1.0F);
		float amplification = clamp(weaknessProduct, 1.0F, AbsorbCaps.DAMAGE_TAKEN_WEAKNESS_CEILING);
		float base = amount * reduction;
		return new Incoming(base, base * (amplification - 1.0F));
	}

	public static float outgoing(float product) {
		return clamp(product, AbsorbCaps.DAMAGE_DEALT_MIN, AbsorbCaps.DAMAGE_DEALT_MAX);
	}

	public static float heal(float product) {
		return clamp(product, AbsorbCaps.HEAL_FACTOR_MIN, AbsorbCaps.HEAL_FACTOR_MAX);
	}

	public static float exhaustion(float product) {
		return clamp(product, AbsorbCaps.EXHAUSTION_FACTOR_MIN, AbsorbCaps.EXHAUSTION_FACTOR_MAX);
	}

	public static float experience(float product) {
		return clamp(product, AbsorbCaps.EXPERIENCE_FACTOR_MIN, AbsorbCaps.EXPERIENCE_FACTOR_MAX);
	}

	public static float durability(float product) {
		return clamp(product, AbsorbCaps.DURABILITY_FACTOR_MIN, AbsorbCaps.DURABILITY_FACTOR_MAX);
	}

	public static double visibility(double product) {
		return Math.clamp(Double.isNaN(product) ? 1.0 : product, AbsorbCaps.VISIBILITY_FACTOR_MIN, AbsorbCaps.VISIBILITY_FACTOR_MAX);
	}

	/** NaN → 1 (no effect), then clamped. */
	static float clamp(float value, float min, float max) {
		if (Float.isNaN(value)) value = 1.0F;
		return Math.clamp(value, min, max);
	}
}
