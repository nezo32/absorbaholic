package dev.absorbaholic.trait.behavior;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerRuntime;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.ActiveSet;
import dev.absorbaholic.trait.Condition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Per-player, per-entry scratch state of the WP-BEH-B behaviors, kept in {@code PlayerRuntime.behaviorState} (transient,
 * server thread only). Keyed by the {@link ActiveBehavior} value (entry, level, source, side), so an entry that survives
 * a rebuild unchanged keeps its timers, while a level change starts fresh. States of entries that left the active set
 * are dropped on the next access; every condition cache is invalidated whenever the set is rebuilt.
 */
final class EntryStates {
	private static final Identifier KEY = Absorbaholic.id("behavior_b");

	private EntryStates() {}

	/** The state of {@code self} (created on first use). Prunes states of entries that are no longer active. */
	static State get(ServerPlayer player, ActiveBehavior<?> self) {
		Store store = store(PlayerData.runtime(player));
		State state = store.byIdentity.get(self);
		if (state == null) {
			state = store.states.computeIfAbsent(self, k -> new State());
			store.byIdentity.put(self, state);
		}
		return state;
	}

	/** The state of {@code self} without pruning or creating (used from onDeactivate, while the set is being replaced). */
	static @Nullable State peek(ServerPlayer player, ActiveBehavior<?> self) {
		Object o = PlayerData.runtime(player).behaviorState.get(KEY);
		return o instanceof Store store ? store.states.get(self) : null;
	}

	/** Forgets the state of {@code self} (onDeactivate). */
	static void remove(ServerPlayer player, ActiveBehavior<?> self) {
		Object o = PlayerData.runtime(player).behaviorState.get(KEY);
		if (o instanceof Store store) {
			store.states.remove(self);
			store.byIdentity.remove(self);
		}
	}

	/**
	 * {@code condition.test(player)} cached for {@link AbsorbCaps#CONDITION_CACHE_TICKS} ticks per entry (behaviors.md
	 * §0.2). An always-true condition costs nothing.
	 */
	static boolean condition(ServerPlayer player, ActiveBehavior<?> self, Condition condition) {
		if (condition.isAlways()) return true;
		State s = get(player, self);
		long now = now(player);
		if (now - s.conditionTick >= AbsorbCaps.CONDITION_CACHE_TICKS || now < s.conditionTick) {
			s.conditionValue = condition.test(player);
			s.conditionTick = now;
		}
		return s.conditionValue;
	}

	static long now(ServerPlayer player) {
		return player.level().getServer().getTickCount();
	}

	private static Store store(PlayerRuntime rt) {
		Store store = (Store) rt.behaviorState.computeIfAbsent(KEY, k -> new Store());
		if (store.set != rt.active) {
			store.set = rt.active;
			Set<ActiveBehavior<?>> live = new HashSet<>(rt.active.all());
			store.states.keySet().retainAll(live);
			store.byIdentity.clear();
			for (State s : store.states.values()) s.conditionTick = Long.MIN_VALUE / 2;
		}
		return store;
	}

	/** All states of one player. */
	private static final class Store {
		@Nullable ActiveSet set;
		final Map<ActiveBehavior<?>, State> states = new HashMap<>();
		/** Fast path for the current set's entry instances (value hashing of params is not free). */
		final Map<ActiveBehavior<?>, State> byIdentity = new IdentityHashMap<>();
	}

	/** One entry's state; each behavior type uses the fields it needs. */
	static final class State {
		long conditionTick = Long.MIN_VALUE / 2;
		boolean conditionValue;
		/** Game time of the last pulse ({@code status_effect} pulse mode); MIN = not started yet. */
		long lastPulse = Long.MIN_VALUE;
		/** {@code mob_attitude} flee: fear is suspended until this game time ({@code pause_on_hit}). */
		long pausedUntil = Long.MIN_VALUE;
		/** Mobs this entry made target the player ({@code mob_attitude} hostile, {@code detection_range}). */
		final Set<Mob> provoked = new HashSet<>();
		/** Recent hits of the player on matching mobs ({@code mob_attitude} ignore: revenge). */
		final Deque<Hit> hits = new ArrayDeque<>();
	}

	/** The player hurt a mob of {@code type} with id {@code mob} at {@code pos} at game time {@code tick}. */
	record Hit(UUID mob, EntityType<?> type, Vec3 pos, long tick) {}
}
