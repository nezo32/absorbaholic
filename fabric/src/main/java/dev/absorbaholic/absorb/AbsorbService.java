package dev.absorbaholic.absorb;

import java.util.Optional;

import dev.absorbaholic.core.MutationRoll;
import dev.absorbaholic.registry.SourceDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * WP-ABSORB. The outcome of a completed channel, separated from the channel so gametests can call it directly:
 * refuse if the trait is maxed; evict the oldest entry at the slot cap (announced); roll; apply levels
 * ({@code LevelStacking}); {@code PlayerData.setTraits}; mark discovered ({@code AbsorbWorldSettings.discover});
 * start the cooldown; {@link AbsorbFeedback#absorbed}.
 */
public final class AbsorbService {
	private AbsorbService() {}

	/** Result of {@link #apply}. {@code evicted} is the source removed to make room, if any. */
	public record Result(boolean success, MutationRoll.Outcome outcome, int traitLevel, int weaknessLevel,
			Optional<Identifier> evicted) {}

	/**
	 * Applies one absorption of {@code source} with a fixed {@code outcome} (no consumption, no feedback). Returns a
	 * failed result (nothing changed) when the trait is already at max level.
	 */
	public static Result apply(ServerPlayer player, SourceDefinition source, MutationRoll.Outcome outcome) {
		// TODO(WP-ABSORB)
		return new Result(false, outcome, 0, 0, Optional.empty());
	}
}
