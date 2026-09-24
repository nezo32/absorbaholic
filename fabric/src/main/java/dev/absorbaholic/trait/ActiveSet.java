package dev.absorbaholic.trait;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The cached, immutable set of a player's active behavior entries, indexed by hook so dispatch only visits entries
 * that override that hook. Also carries the OR of their {@link MovementFlags}. Rebuilt by the engine on change.
 */
public final class ActiveSet {
	public static final ActiveSet EMPTY = new ActiveSet(List.of());

	private static final ActiveBehavior<?>[] NONE = new ActiveBehavior<?>[0];

	private final List<ActiveBehavior<?>> all;
	private final Map<Hook, ActiveBehavior<?>[]> byHook = new EnumMap<>(Hook.class);
	private final int movementFlags;

	public ActiveSet(List<ActiveBehavior<?>> entries) {
		this.all = List.copyOf(entries);
		Map<Hook, List<ActiveBehavior<?>>> lists = new EnumMap<>(Hook.class);
		int flags = 0;
		for (ActiveBehavior<?> a : all) {
			for (Hook h : a.type().hooks()) lists.computeIfAbsent(h, k -> new ArrayList<>()).add(a);
			if (a.has(Hook.MOVEMENT_FLAGS)) flags |= a.movementFlags();
		}
		lists.forEach((h, l) -> byHook.put(h, l.toArray(NONE)));
		this.movementFlags = flags;
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

	public int movementFlags() {
		return movementFlags;
	}

	public boolean isEmpty() {
		return all.isEmpty();
	}
}
