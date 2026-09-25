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
		DamageGate gate2 = new DamageGate(8F, 20, 1F);
		assertEquals(6F, gate2.allowExtra(2, 6F, 12F, 9F, 10F), "never full in the window: budget 8");
	}

	/** Review M1: max health 6 (the clamp floor), rain + snowball in one window used to kill from full health. */
	@Test
	void wholeWindowKeepsAFullHealthPlayerAlive() {
		DamageGate gate = new DamageGate(8F, 20, 1F);
		assertEquals(5F, gate.budgetFor(6F), "budget = min(8, maxHealth - 1)");
		assertEquals(2F, gate.allowDirect(0, 2F, 6F, 6F), "rain: 6 -> 4");
		assertEquals(3F, gate.allowDirect(5, 4F, 4F, 6F), "snowball: at most down to 1 HP, not 4 → dead");
		assertEquals(0F, gate.allowDirect(10, 1F, 1F, 6F), "nothing more this window");
		// tick 21: the full-health sighting (tick 0) left the window; 3 HP of the budget are still spent (tick 5)
		assertEquals(1F, gate.allowDirect(21, 1F, 2F, 6F), "a new window without a full-health sighting: budget again");
	}

	@Test
	void fullHealthSightingByObserveCountsForTheWholeWindow() {
		DamageGate gate = new DamageGate(8F, 20, 1F);
		gate.observe(0, 20F, 20F);
		// a zombie (not weakness damage) took the player to 3 HP at tick 5; weakness damage may not finish the job
		assertEquals(2F, gate.allowDirect(10, 4F, 3F, 20F), "full at tick 0: never below 1 HP until tick 20");
		assertEquals(4F, gate.allowDirect(20, 4F, 5F, 20F), "tick 20: the sighting left the window");
	}

	@Test
	void budgetScalesWithLowMaxHealth() {
		DamageGate gate = new DamageGate(8F, 20, 1F);
		assertEquals(5F, gate.allowDirect(0, 8F, 5F, 6F), "never full: budget min(8, 6 - 1) = 5");
		assertEquals(0F, gate.allowDirect(1, 1F, 1F, 6F), "budget spent");
		assertEquals(8F, new DamageGate(8F, 20, 1F).budgetFor(60F));
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
