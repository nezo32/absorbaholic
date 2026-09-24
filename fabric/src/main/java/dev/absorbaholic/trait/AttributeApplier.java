package dev.absorbaholic.trait;

import dev.absorbaholic.player.PlayerTraits;
import net.minecraft.server.level.ServerPlayer;

/**
 * WP-ENGINE. Applies the attribute parts of a player's active traits and weaknesses: per (attribute, operation) one
 * summed modifier with id {@code absorbaholic:<operation>} (so removal is by id and never touches other mods), then
 * per clamped attribute ({@code AbsorbCaps.clampFor}) one correcting add_multiplied_total modifier
 * {@code absorbaholic:clamp} computed with {@code AttributeClamp}. Removes all of ours when dormant. Keeps health
 * &lt;= max health after max_health changes. {@link #reclamp} recomputes only the clamp modifiers (periodic).
 */
public final class AttributeApplier {
	private AttributeApplier() {}

	/** {@code traits} null or dormant player → remove all our modifiers. */
	public static void apply(ServerPlayer player, PlayerTraits traits, boolean active) {
		// TODO(WP-ENGINE)
	}

	public static void reclamp(ServerPlayer player) {
		// TODO(WP-ENGINE)
	}
}
