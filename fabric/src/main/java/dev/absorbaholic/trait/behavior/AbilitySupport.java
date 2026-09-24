package dev.absorbaholic.trait.behavior;

import java.util.function.Supplier;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerRuntime;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Condition;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Shared plumbing of the movement and ability behaviors (WP-BEH-C). Every timer of an entry lives in
 * {@code PlayerRuntime.abilityCooldowns} as an end tick (server tick count) under {@link #key}, optionally with a
 * suffix ("lock", "fuse", …), so tests and other code can read them; other per-entry scratch state lives in
 * {@code PlayerRuntime.behaviorState} under the same key. Server thread only.
 */
public final class AbilitySupport {
	private AbilitySupport() {}

	/**
	 * The per-player key of one active entry: {@code absorbaholic:<type path>/<source namespace>/<source path>}, plus
	 * {@code /weakness} on the weakness side (a source may use the same type on both sides, e.g. chorus teleport).
	 */
	public static Identifier key(ActiveBehavior<?> self) {
		Identifier source = self.sourceId();
		return Absorbaholic.id(self.type().id().getPath() + "/" + source.getNamespace() + "/" + source.getPath() + (self.weakness() ? "/weakness" : ""));
	}

	/** {@link #key} plus {@code /<suffix>}, for secondary timers of the same entry. */
	public static Identifier key(ActiveBehavior<?> self, String suffix) {
		Identifier base = key(self);
		return Absorbaholic.id(base.getPath() + "/" + suffix);
	}

	/** The engine's clock (server tick count). */
	public static long now(ServerPlayer player) {
		return player.level().getServer().getTickCount();
	}

	/** True when the timer {@code key} has run out (or was never set). */
	public static boolean elapsed(ServerPlayer player, Identifier key) {
		Long end = PlayerData.runtime(player).abilityCooldowns.get(key);
		return end == null || end <= now(player);
	}

	/** True while timer {@code key} is set (running or run out but not cleared). */
	public static boolean hasTimer(ServerPlayer player, Identifier key) {
		return PlayerData.runtime(player).abilityCooldowns.containsKey(key);
	}

	/** True when the entry's ability cooldown has run out. */
	public static boolean ready(ServerPlayer player, ActiveBehavior<?> self) {
		return elapsed(player, key(self));
	}

	/** Sets timer {@code key} to end {@code ticks} (&gt;= 0) from now. */
	public static void setTimer(ServerPlayer player, Identifier key, long ticks) {
		PlayerData.runtime(player).abilityCooldowns.put(key, now(player) + Math.max(0L, ticks));
	}

	/** Clears timer {@code key}. */
	public static void clearTimer(ServerPlayer player, Identifier key) {
		PlayerData.runtime(player).abilityCooldowns.remove(key);
	}

	/**
	 * Starts the entry's ability cooldown: {@code ticks} rounded, never shorter than
	 * {@link AbsorbCaps#ABILITY_MIN_COOLDOWN_TICKS}. Returns the applied length.
	 */
	public static int startCooldown(ServerPlayer player, ActiveBehavior<?> self, double ticks) {
		int length = Math.max(AbsorbCaps.ABILITY_MIN_COOLDOWN_TICKS, ticksOf(ticks));
		setTimer(player, key(self), length);
		return length;
	}

	/** A tick count from a (possibly broken) number: rounded, NaN and negatives become 0. */
	public static int ticksOf(double value) {
		if (!(value > 0.0)) return 0;
		return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.round(value);
	}

	/** {@code value.at(level)} with NaN / infinities replaced by 0. */
	public static double at(LevelValue value, int level) {
		double v = value.at(level);
		return Double.isFinite(v) ? v : 0.0;
	}

	/** The entry's scratch state, created with {@code factory} when absent or of another type. */
	public static <T> T state(ServerPlayer player, ActiveBehavior<?> self, Class<T> type, Supplier<T> factory) {
		PlayerRuntime rt = PlayerData.runtime(player);
		Identifier key = key(self);
		Object current = rt.behaviorState.get(key);
		if (type.isInstance(current)) return type.cast(current);
		T created = factory.get();
		rt.behaviorState.put(key, created);
		return created;
	}

	/** The entry's scratch state, or null. */
	public static <T> T peekState(ServerPlayer player, ActiveBehavior<?> self, Class<T> type) {
		Object current = PlayerData.runtime(player).behaviorState.get(key(self));
		return type.isInstance(current) ? type.cast(current) : null;
	}

	/**
	 * Sends {@code entity}'s current velocity to its trackers and itself right away. Portable replacement for the
	 * velocity-sync flag (26.2 {@code hurtMarked}, 26.3 {@code syncVelocity}); players need it because they move
	 * client side.
	 */
	public static void syncMotion(Entity entity) {
		if (entity.level() instanceof ServerLevel level) {
			level.getChunkSource().sendToTrackingPlayersAndSelf(entity, new ClientboundSetEntityMotionPacket(entity));
		}
	}

	/** Drops the entry's scratch state. */
	public static void clearState(ServerPlayer player, ActiveBehavior<?> self) {
		PlayerData.runtime(player).behaviorState.remove(key(self));
	}

	/**
	 * The condition of a periodic behavior, evaluated at most every {@link AbsorbCaps#CONDITION_CACHE_TICKS} ticks
	 * (behaviors.md §0.2); {@code cache} is the entry's state. Trigger-time checks call {@code Condition#test} directly.
	 */
	public static boolean holds(ServerPlayer player, Condition condition, ConditionCache cache) {
		if (condition.isAlways()) return true;
		long now = now(player);
		if (now >= cache.conditionUntil) {
			cache.condition = condition.test(player);
			cache.conditionUntil = now + AbsorbCaps.CONDITION_CACHE_TICKS;
		}
		return cache.condition;
	}

	/** Per-entry scratch state that caches the entry's condition (extend it for more fields). */
	public static class ConditionCache {
		long conditionUntil = Long.MIN_VALUE;
		boolean condition;
	}
}
