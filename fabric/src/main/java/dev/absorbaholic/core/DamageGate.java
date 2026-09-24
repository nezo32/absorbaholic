package dev.absorbaholic.core;

import java.util.ArrayDeque;

/**
 * Per-player limiter of weakness damage: all direct weakness damage (rain, sunlight, water …) and the extra part that
 * weakness multipliers add to incoming damage go through one gate that allows at most {@code budget} damage per
 * rolling window of {@code windowTicks}, and never lets a single weakness hit take a full-health player below
 * {@code minHealthFromFull}. Pure; the caller passes the current game time. Not thread safe (server thread only).
 */
public final class DamageGate {
	private record Spent(long tick, float amount) {}

	private final float budget;
	private final int windowTicks;
	private final float minHealthFromFull;
	private final ArrayDeque<Spent> spent = new ArrayDeque<>();
	private float spentTotal;

	public DamageGate() {
		this(AbsorbCaps.WEAKNESS_DAMAGE_BUDGET, AbsorbCaps.WEAKNESS_DAMAGE_WINDOW_TICKS, AbsorbCaps.WEAKNESS_MIN_HEALTH_FROM_FULL);
	}

	public DamageGate(float budget, int windowTicks, float minHealthFromFull) {
		this.budget = budget;
		this.windowTicks = windowTicks;
		this.minHealthFromFull = minHealthFromFull;
	}

	/** Budget left at {@code now}. */
	public float remaining(long now) {
		expire(now);
		return Math.max(0.0F, budget - spentTotal);
	}

	/**
	 * Direct weakness damage: returns how much of {@code requested} may be dealt now and records it as spent.
	 * {@code health}/{@code maxHealth} are the player's current values.
	 */
	public float allowDirect(long now, float requested, float health, float maxHealth) {
		return allowExtra(now, requested, 0.0F, health, maxHealth);
	}

	/**
	 * The extra part a weakness multiplier adds on top of {@code baseDamage} (the damage without weaknesses):
	 * returns how much extra may be added and records it as spent. The full-health rule counts the base damage too,
	 * so base + extra never takes a full-health player below the minimum (the base alone may, as without the mod).
	 */
	public float allowExtra(long now, float extra, float baseDamage, float health, float maxHealth) {
		if (!(extra > 0.0F)) return 0.0F; // also NaN
		float allowed = Math.min(extra, remaining(now));
		if (health >= maxHealth) {
			allowed = Math.min(allowed, Math.max(0.0F, health - minHealthFromFull - Math.max(0.0F, baseDamage)));
		}
		if (allowed > 0.0F) {
			spent.addLast(new Spent(now, allowed));
			spentTotal += allowed;
		}
		return allowed;
	}

	/** Forgets everything (death, respawn). */
	public void reset() {
		spent.clear();
		spentTotal = 0.0F;
	}

	private void expire(long now) {
		while (!spent.isEmpty() && now - spent.peekFirst().tick() >= windowTicks) {
			spentTotal -= spent.removeFirst().amount();
		}
		if (spent.isEmpty()) spentTotal = 0.0F; // no float drift
	}
}
