package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DamageGateTest {
	@Test
	void budgetPerRollingWindow() {
		DamageGate gate = new DamageGate(8F, 20, 1F);
		assertEquals(5F, gate.allowDirect(0, 5F, 10F, 20F));
		assertEquals(3F, gate.allowDirect(5, 5F, 10F, 20F));
		assertEquals(0F, gate.allowDirect(19, 1F, 10F, 20F));
		assertEquals(5F, gate.remaining(20), 1e-6, "first hit expired after 20 ticks");
		assertEquals(8F, gate.remaining(25), 1e-6);
	}

	@Test
	void fullHealthNeverBelowOneHp() {
		DamageGate gate = new DamageGate(8F, 20, 1F);
		assertEquals(5F, gate.allowDirect(0, 8F, 6F, 6F), "6 HP full → at most 5");
		DamageGate gate2 = new DamageGate(8F, 20, 1F);
		assertEquals(8F, gate2.allowDirect(0, 8F, 6F, 20F), "not full health: only the budget applies");
	}

	@Test
	void extraCountsBaseDamageAtFullHealth() {
		DamageGate gate = new DamageGate(8F, 20, 1F);
		assertEquals(2F, gate.allowExtra(0, 6F, 7F, 10F, 10F), "10 - 1 - 7 = 2");
		assertEquals(0F, gate.allowExtra(1, 6F, 12F, 10F, 10F), "base alone is lethal: no extra");
		assertEquals(6F, gate.allowExtra(2, 6F, 12F, 9F, 10F), "not full: budget 8 - 2 = 6");
	}

	@Test
	void ignoresNonPositive() {
		DamageGate gate = new DamageGate();
		assertEquals(0F, gate.allowDirect(0, 0F, 20F, 20F));
		assertEquals(0F, gate.allowDirect(0, -3F, 20F, 20F));
		assertEquals(0F, gate.allowDirect(0, Float.NaN, 20F, 20F));
		assertEquals(AbsorbCaps.WEAKNESS_DAMAGE_BUDGET, gate.remaining(0));
	}
}
