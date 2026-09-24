package dev.absorbaholic.trait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.AttributeClamp;
import org.junit.jupiter.api.Test;

/** Pure parts of the trait engine: the attribute clamp solver, input-edge classification and stochastic rounding. */
class EngineMathTest {
	/** Final value after applying a solution (our modifiers scaled, then the clamp factor). */
	private static double apply(AttributeApplier.Solution s, double base, double oAdd, double oBase, double oTotal,
			double add, double mulBase, double total) {
		double scaled = AttributeClamp.finalValue(base, oAdd + add * s.scale(), oBase + mulBase * s.scale(), oTotal * (1.0 + (total - 1.0) * s.scale()));
		return scaled * (1.0 + s.correction());
	}

	@Test
	void inRangeNeedsNothing() {
		AttributeApplier.Solution s = AttributeApplier.solve(0.1, 0, 0, 1, 0, 0, 1.5, 0.04, 0.2);
		assertEquals(1.0, s.scale());
		assertEquals(0.0, s.correction());
	}

	@Test
	void multiplicativeCorrectionReachesTheBound() {
		// movement speed ×6 → ×2 of base
		AttributeApplier.Solution s = AttributeApplier.solve(0.1, 0, 0, 1, 0, 0, 6.0, 0.04, 0.2);
		assertEquals(1.0, s.scale());
		assertEquals(0.2, apply(s, 0.1, 0, 0, 1, 0, 0, 6.0), 1e-12);
		// max health +100 → 60
		s = AttributeApplier.solve(20, 0, 0, 1, 100, 0, 1, 6, 60);
		assertEquals(60.0, apply(s, 20, 0, 0, 1, 100, 0, 1), 1e-9);
		// others included: sprint ×1.3 on top of our ×1.9
		s = AttributeApplier.solve(0.1, 0, 0, 1.3, 0, 0, 1.9, 0.04, 0.2);
		assertEquals(0.2, apply(s, 0.1, 0, 0, 1.3, 0, 0, 1.9), 1e-12);
	}

	@Test
	void othersAlreadyOutOfRangeAreNotPushedFurther() {
		// without ours ×3 (0.3), ours would add ×1.9 → capped at the value without ours
		AttributeApplier.Solution s = AttributeApplier.solve(0.1, 0, 0, 3.0, 0, 0, 1.9, 0.04, 0.2);
		assertEquals(0.3, apply(s, 0.1, 0, 0, 3.0, 0, 0, 1.9), 1e-12);
	}

	@Test
	void degenerateValuesScaleOursDown() {
		// max health 20 - 100 = -80: no factor can reach 6, so ours shrink
		AttributeApplier.Solution s = AttributeApplier.solve(20, 0, 0, 1, -100, 0, 1, 6, 60);
		assertTrue(s.scale() < 1.0);
		double v = apply(s, 20, 0, 0, 1, -100, 0, 1);
		assertTrue(v >= 6 - 1e-9 && v <= 60 + 1e-9, "in range: " + v);
		// jump strength × (1 - 1) = 0
		s = AttributeApplier.solve(0.42, 0, 0, 1, 0, -1.0, 1, 0.2, 1.2);
		v = apply(s, 0.42, 0, 0, 1, 0, -1.0, 1);
		assertEquals(0.2, v, 1e-9);
		// multiplied total -1 (product 0)
		s = AttributeApplier.solve(0.08, 0, 0, 1, 0, 0, 0.0, 0.02, 0.16);
		v = apply(s, 0.08, 0, 0, 1, 0, 0, 0.0);
		assertTrue(v >= 0.02 - 1e-12 && v <= 0.16 + 1e-12, "in range: " + v);
	}

	@Test
	void jumpClassification() {
		assertEquals(Hook.JUMP, TraitEngine.InputEdges.jump(true, true, false, false));
		assertEquals(Hook.SNEAK_JUMP, TraitEngine.InputEdges.jump(true, true, false, true));
		// the move packet that left the ground arrived in the same tick as the input packet
		assertEquals(Hook.JUMP, TraitEngine.InputEdges.jump(false, true, true, false));
		assertEquals(Hook.SNEAK_JUMP, TraitEngine.InputEdges.jump(false, true, true, true));
		assertEquals(Hook.AIR_JUMP, TraitEngine.InputEdges.jump(false, false, true, false));
		assertEquals(Hook.AIR_JUMP, TraitEngine.InputEdges.jump(false, false, true, true));
		assertNull(TraitEngine.InputEdges.jump(false, false, false, false), "swimming / climbing: nothing");
	}

	@Test
	void doubleTapWindow() {
		assertTrue(TraitEngine.InputEdges.doubleTap(100, 101));
		assertTrue(TraitEngine.InputEdges.doubleTap(100, 100 + AbsorbCaps.SNEAK_DOUBLE_TAP_TICKS));
		assertFalse(TraitEngine.InputEdges.doubleTap(100, 101 + AbsorbCaps.SNEAK_DOUBLE_TAP_TICKS));
		assertFalse(TraitEngine.InputEdges.doubleTap(100, 100), "same tick is not a second tap");
		assertFalse(TraitEngine.InputEdges.doubleTap(Long.MIN_VALUE / 2, 5), "no previous edge");
	}

	@Test
	void stochasticRounding() {
		assertEquals(3, TraitEngine.InputEdges.roundStochastic(3.0, 0.0));
		assertEquals(3, TraitEngine.InputEdges.roundStochastic(3.25, 0.3));
		assertEquals(4, TraitEngine.InputEdges.roundStochastic(3.25, 0.2));
		assertEquals(0, TraitEngine.InputEdges.roundStochastic(-2.0, 0.0));
		assertEquals(0, TraitEngine.InputEdges.roundStochastic(Double.NaN, 0.0));
		assertEquals(Integer.MAX_VALUE, TraitEngine.InputEdges.roundStochastic(1e300, 0.5));
		// expectation is preserved: 0.25 per unit on average
		double sum = 0;
		int n = 10_000;
		for (int i = 0; i < n; i++) sum += TraitEngine.InputEdges.roundStochastic(0.25, (i + 0.5) / n);
		assertEquals(0.25, sum / n, 1e-3);
	}
}
