package dev.absorbaholic.absorb;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelStacking;
import dev.absorbaholic.core.MutationRoll;
import dev.absorbaholic.net.ChannelStatePayload;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.world.AbsorbWorldSettings;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

/**
 * The outcome of a completed channel, separate from the channel so gametests can drive it directly.
 * {@link #complete}: refuse if the trait is maxed (nothing consumed), consume the target (blocks and fluid sources
 * through {@link BlockRemoval}; entities {@code removeAllEffects()} then {@code discard()}, no loot, XP or death
 * event; the Ender Dragon first marks its fight killed so the exit portal and egg appear), {@link #apply} the levels,
 * then {@link AbsorbFeedback#absorbed}. {@link #apply}: evict the oldest entries at the slot cap, stack the levels
 * ({@link LevelStacking}), store the traits, mark the source discovered and start the cooldown.
 */
public final class AbsorbService {
	private AbsorbService() {}

	/**
	 * Result of {@link #apply}. {@code evicted} are the sources removed to make room (oldest first; normally at most
	 * one, more only after {@code /absorbaholic max} was lowered below a player's count).
	 */
	public record Result(boolean success, MutationRoll.Outcome outcome, int traitLevel, int weaknessLevel,
			List<Identifier> evicted) {
		public Result {
			evicted = List.copyOf(evicted);
		}

		static Result refused(MutationRoll.Outcome outcome) {
			return new Result(false, outcome, 0, 0, List.of());
		}
	}

	/** {@link #apply(ServerPlayer, SourceDefinition, MutationRoll.Outcome, long)} at the current game time. */
	public static Result apply(ServerPlayer player, SourceDefinition source, MutationRoll.Outcome outcome) {
		return apply(player, source, outcome, AbsorbHandler.now(player.level().getServer()));
	}

	/**
	 * Applies one absorption of {@code source} with a fixed {@code outcome} (no consumption, no feedback). Returns a
	 * failed result (nothing changed) when the trait is already at max level.
	 */
	public static Result apply(ServerPlayer player, SourceDefinition source, MutationRoll.Outcome outcome, long now) {
		MinecraftServer server = player.level().getServer();
		PlayerTraits traits = PlayerData.traits(player);
		Optional<TraitEntry> existing = traits.get(source.id());
		LevelStacking.Levels current = existing
				.map(e -> new LevelStacking.Levels(e.traitLevel(), e.weaknessLevel()))
				.orElse(LevelStacking.Levels.NONE);
		if (!LevelStacking.canAbsorb(current.trait(), source.maxLevel())) return Result.refused(outcome);

		List<Identifier> evicted = new ArrayList<>();
		if (existing.isEmpty()) {
			int max = AbsorbWorldSettings.get(server).maxTraits();
			while (traits.size() >= max && traits.oldest().isPresent()) {
				Identifier oldest = traits.oldest().get().source();
				traits = traits.without(oldest);
				evicted.add(oldest);
			}
		}
		LevelStacking.Levels next = LevelStacking.apply(current, source.maxLevel(), outcome);
		boolean mutated = existing.map(TraitEntry::mutated).orElse(false) || outcome.isMutation();
		boolean pure = existing.map(TraitEntry::pure).orElse(false) || outcome == MutationRoll.Outcome.PURE;
		PlayerData.setTraits(player, traits.with(new TraitEntry(source.id(), next.trait(), next.weakness(), mutated, pure)));
		AbsorbWorldSettings.discover(server, source.id());
		PlayerData.runtime(player).absorbCooldownUntil = now + AbsorbCaps.COOLDOWN_TICKS;
		return new Result(true, outcome, next.trait(), next.weakness(), evicted);
	}

	/**
	 * Completes an absorption of a validated target with {@code outcome}: refuses (nothing consumed, refusal shown)
	 * if the trait is maxed; else consumes the target, applies the levels and plays the feedback, and tells a modded
	 * client the channel COMPLETED.
	 */
	public static Result complete(ServerPlayer player, AbsorbHandler.Resolved target, MutationRoll.Outcome outcome, long now) {
		SourceDefinition source = target.source();
		int traitLevel = PlayerData.traits(player).get(source.id()).map(TraitEntry::traitLevel).orElse(0);
		if (!LevelStacking.canAbsorb(traitLevel, source.maxLevel())) {
			AbsorbFeedback.refused(player, AbsorbFeedback.reason(AbsorbFeedback.REFUSE_MAX_LEVEL, AbsorbFeedback.traitName(source)));
			return Result.refused(outcome);
		}
		consume(player.level(), target);
		Result result = apply(player, source, outcome, now);
		AbsorbHandler.sendState(player, ChannelStatePayload.Status.COMPLETED, AbsorbCaps.CHANNEL_TICKS);
		AbsorbFeedback.absorbed(player, target.name(), source, result);
		return result;
	}

	/** Removes the absorbed block, fluid source or entity without drops, experience or death events. */
	static void consume(ServerLevel level, AbsorbHandler.Resolved target) {
		switch (target.target().kind()) {
			case BLOCK -> BlockRemoval.removeSilently(level, target.target().pos(), false);
			case FLUID -> BlockRemoval.removeSilently(level, target.target().pos(), true);
			case ENTITY -> {
				if (target.entity() != null) consumeEntity(level, target.entity());
			}
		}
	}

	/**
	 * Discards {@code entity} with no loot, XP or death event. Effects are removed first: {@code remove} still runs
	 * their on-removal logic (oozing, weaving, infested). An Ender Dragon first notifies its level's dragon fight.
	 */
	public static void consumeEntity(ServerLevel level, LivingEntity entity) {
		if (entity instanceof EnderDragon dragon) notifyDragonFight(level, dragon);
		entity.removeAllEffects();
		entity.discard();
	}

	/**
	 * Marks the dragon fight of {@code level} (if any) as won by {@code dragon}: the exit portal, gateway and (first
	 * time) the egg appear, exactly as after a kill. The fight ignores a dragon that is not its own. Returns true if
	 * the level has a dragon fight.
	 */
	public static boolean notifyDragonFight(ServerLevel level, EnderDragon dragon) {
		EnderDragonFight fight = level.getDragonFight();
		if (fight == null) return false;
		fight.setDragonKilled(dragon);
		return true;
	}
}
