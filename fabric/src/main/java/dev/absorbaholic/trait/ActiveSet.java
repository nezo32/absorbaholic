package dev.absorbaholic.trait;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The cached, immutable set of a player's active behavior entries, indexed by hook so dispatch only visits entries
 * that override that hook, in trait acquisition order (the order triggers are offered in). Also carries the merged
 * {@link MovementState}. Rebuilt by the engine on change.
 */
public final class ActiveSet {
	public static final ActiveSet EMPTY = new ActiveSet(List.of());

	private static final ActiveBehavior<?>[] NONE = new ActiveBehavior<?>[0];

	private final List<ActiveBehavior<?>> all;
	private final Map<Hook, ActiveBehavior<?>[]> byHook = new EnumMap<>(Hook.class);
	private final MovementState movement;

	public ActiveSet(List<ActiveBehavior<?>> entries) {
		this.all = List.copyOf(entries);
		Map<Hook, List<ActiveBehavior<?>>> lists = new EnumMap<>(Hook.class);
		MovementState move = MovementState.NONE;
		for (ActiveBehavior<?> a : all) {
			for (Hook h : a.type().hooks()) lists.computeIfAbsent(h, k -> new ArrayList<>()).add(a);
			if (a.has(Hook.MOVEMENT)) move = move.merge(a.movement());
		}
		lists.forEach((h, l) -> byHook.put(h, l.toArray(NONE)));
		this.movement = move;
	}

	public List<ActiveBehavior<?>> all() {
		return all;
	}

	/** Entries overriding {@code hook}; never null. Do not modify the returned array. */
	public ActiveBehavior<?>[] forHook(Hook hook) {
		return byHook.getOrDefault(hook, NONE);
	}

	public boolean any(Hook hook) {
		return byHook.containsKey(hook);
	}

	public MovementState movement() {
		return movement;
	}

	public boolean isEmpty() {
		return all.isEmpty();
	}
}
