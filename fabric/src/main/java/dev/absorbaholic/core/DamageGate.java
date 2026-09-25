package dev.absorbaholic.core;

import java.util.ArrayDeque;

/**
 * Per-player limiter of weakness damage: all direct weakness damage (rain, sunlight, water …), the extra part that
 * weakness multipliers add to incoming damage, and vanilla damage a weakness caused (its burning, its starvation) go
 * through one gate. Per rolling window of {@code windowTicks} it allows at most
 * {@code min(budget, maxHealth - minHealthFromFull)}, and while the player was at full health at any point of the
 * current window (seen by a gate call or by {@link #observe}), weakness damage never takes it below
 * {@code minHealthFromFull}: every hit of the window together, not just the first one. Pure; the caller passes the
 * current game time. Not thread safe (server thread only).
 */
public final class DamageGate {
	private record Spent(long tick, float amount) {}

	private final float budget;
	private final int windowTicks;
	private final float minHealthFromFull;
	private final ArrayDeque<Spent> spent = new ArrayDeque<>();
	private float spentTotal;
	/** Last game time the player was seen at full health. */
	private long lastFullTick = Long.MIN_VALUE / 2;

	public DamageGate() {
		this(AbsorbCaps.WEAKNESS_DAMAGE_BUDGET, AbsorbCaps.WEAKNESS_DAMAGE_WINDOW_TICKS, AbsorbCaps.WEAKNESS_MIN_HEALTH_FROM_FULL);
	}

	public DamageGate(float budget, int windowTicks, float minHealthFromFull) {
		this.budget = budget;
		this.windowTicks = windowTicks;
		this.minHealthFromFull = minHealthFromFull;
	}

	/** The budget of a window for a player with {@code maxHealth}: {@code min(budget, maxHealth - minHealthFromFull)}. */
	public float budgetFor(float maxHealth) {
		return Math.max(0.0F, Math.min(budget, maxHealth - minHealthFromFull));
	}

	/** Budget left at {@code now} for a player with {@code maxHealth}. */
	public float remaining(long now, float maxHealth) {
		expire(now);
		return Math.max(0.0F, budgetFor(maxHealth) - spentTotal);
	}

	/** Budget left at {@code now}, ignoring the max-health limit of the budget. */
	public float remaining(long now) {
		return remaining(now, Float.POSITIVE_INFINITY);
	}

	/** The engine reports the player's health every tick, so "full at any point of the window" sees every full tick. */
	public void observe(long now, float health, float maxHealth) {
		if (health >= maxHealth) lastFullTick = now;
	}

	/** True if the player was seen at full health within the window ending at {@code now}. */
	public boolean fullInWindow(long now) {
		return now - lastFullTick < windowTicks;
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
	 * so base + extra never takes a player who was full in this window below the minimum (the base alone may, as
	 * without the mod).
	 */
	public float allowExtra(long now, float extra, float baseDamage, float health, float maxHealth) {
		if (!(extra > 0.0F)) return 0.0F; // also NaN
		observe(now, health, maxHealth);
		float allowed = Math.min(extra, remaining(now, maxHealth));
		if (fullInWindow(now)) {
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
		lastFullTick = Long.MIN_VALUE / 2;
	}

	private void expire(long now) {
		while (!spent.isEmpty() && now - spent.peekFirst().tick() >= windowTicks) {
			spentTotal -= spent.removeFirst().amount();
		}
		if (spent.isEmpty()) spentTotal = 0.0F; // no float drift
	}
}
