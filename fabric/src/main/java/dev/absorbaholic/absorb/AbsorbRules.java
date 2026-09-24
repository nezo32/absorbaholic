package dev.absorbaholic.absorb;

import net.minecraft.world.entity.player.Player;

/**
 * Hand / pose / game-mode preconditions of absorbing, shared by the server validation and the client's use-key
 * intercept (common code, no client classes). The world mode, target and cooldown checks are separate.
 */
public final class AbsorbRules {
	private AbsorbRules() {}

	/** Sneaking, empty main hand, not creative / spectator. Uses the player's own view of its game mode. */
	public static boolean poseAllows(Player player) {
		return player.isShiftKeyDown()
				&& player.getMainHandItem().isEmpty()
				&& !player.isSpectator()
				&& !player.isCreative()
				&& player.isAlive();
	}
}
