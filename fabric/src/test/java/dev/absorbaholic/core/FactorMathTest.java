package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FactorMathTest {
	@Test
	void incomingFloorAndCeiling() {
		assertEquals(new FactorMath.Incoming(2.5F, 0F), FactorMath.incoming(10F, 0.1F, 1F), "floor 0.25");
		assertEquals(new FactorMath.Incoming(10F, 20F), FactorMath.incoming(10F, 1F, 5F), "ceiling 3");
		assertEquals(new FactorMath.Incoming(5F, 2.5F), FactorMath.incoming(10F, 0.5F, 1.5F));
		assertEquals(new FactorMath.Incoming(10F, 0F), FactorMath.incoming(10F, 2F, 0.5F), "traits never amplify, weaknesses never protect");
		assertEquals(new FactorMath.Incoming(10F, 0F), FactorMath.incoming(10F, Float.NaN, Float.NaN));
		assertEquals(new FactorMath.Incoming(0F, 0F), FactorMath.incoming(-1F, 0.5F, 2F));
	}

	@Test
	void otherFactors() {
		assertEquals(AbsorbCaps.DAMAGE_DEALT_MAX, FactorMath.outgoing(10F));
		assertEquals(AbsorbCaps.HEAL_FACTOR_MIN, FactorMath.heal(0F));
		assertEquals(AbsorbCaps.EXHAUSTION_FACTOR_MIN, FactorMath.exhaustion(0F));
		assertEquals(1.0, FactorMath.visibility(Double.NaN));
	}
}
