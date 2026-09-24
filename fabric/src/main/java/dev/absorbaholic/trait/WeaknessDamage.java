package dev.absorbaholic.trait;

import net.minecraft.server.level.ServerPlayer;

/**
 * WP-ENGINE. The only way behaviors deal direct weakness damage: {@code absorbaholic:weakness} damage type
 * (data/absorbaholic/damage_type/weakness.json; tags bypasses_armor, no_knockback; death message
 * {@code death.attack.absorbaholic.weakness}), amount limited by the player's {@code DamageGate}. The engine skips
 * weakness multipliers for this damage type, so it is never amplified twice.
 */
public final class WeaknessDamage {
	private WeaknessDamage() {}

	/** Deals up to {@code amount} (after the gate); returns the amount actually requested from vanilla. */
	public static float hurt(ServerPlayer player, float amount) {
		// TODO(WP-ENGINE)
		return 0.0F;
	}
}
