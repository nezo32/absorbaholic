package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AttributeClampTest {
	@Test
	void vanillaFormula() {
		assertEquals(0.13, AttributeClamp.finalValue(0.1, 0.0, 0.0, 1.3), 1e-9);
		assertEquals(30.0, AttributeClamp.finalValue(20, 10, 0, 1), 1e-9);
		assertEquals(36.0, AttributeClamp.finalValue(20, 10, 0.2, 1), 1e-9);
	}

	@Test
	void targetNeverPushesFurtherOut() {
		assertEquals(2.0, AttributeClamp.target(1.0, 3.0, 0.5, 2.0));
		assertEquals(0.5, AttributeClamp.target(1.0, 0.1, 0.5, 2.0));
		assertEquals(3.0, AttributeClamp.target(3.0, 4.0, 0.5, 2.0), "already above by other sources: ours may not add");
		assertEquals(2.5, AttributeClamp.target(3.0, 2.5, 0.5, 2.0), "moving back toward the range is fine");
	}

	@Test
	void correctionFactor() {
		double withOurs = 0.25, target = 0.2;
		double c = AttributeClamp.correction(withOurs, target);
		assertEquals(target, withOurs * (1 + c), 1e-12);
		assertEquals(0.0, AttributeClamp.correction(0.0, 1.0));
		assertEquals(0.0, AttributeClamp.correction(1.0, 1.0));
	}

	@Test
	void capsTable() {
		AbsorbCaps.Clamp speed = AbsorbCaps.clampFor("minecraft:movement_speed").orElseThrow();
		assertEquals(0.04, speed.lower(0.1), 1e-12);
		assertEquals(0.2, speed.upper(0.1), 1e-12);
		assertEquals(21.0, AbsorbCaps.clampFor("minecraft:attack_damage").orElseThrow().upper(1.0), 1e-12);
		assertTrue(AbsorbCaps.clampFor("minecraft:nope").isEmpty());
		assertEquals(AbsorbCaps.CLAMPS.size(), AbsorbCaps.CLAMPS.stream().map(AbsorbCaps.Clamp::attribute).distinct().count());
	}
}
