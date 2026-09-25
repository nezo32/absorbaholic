package dev.absorbaholic.trait.behavior;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerRuntime;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Shared plumbing of the movement and ability behaviors (WP-BEH-C). Every timer of an entry lives in
 * {@code PlayerRuntime.abilityCooldowns} as an end tick (server tick count) under {@link #key}, optionally with a
 * suffix ("lock", "fuse", …), so tests and other code can read them; other per-entry scratch state lives in
 * {@code PlayerRuntime.behaviorState} under the same key. Server thread only.
 */
public final class AbilitySupport {
	private AbilitySupport() {}

	/**
	 * Key cache: type → source id → keys of the trait [0] and weakness [1] side. Bounded by the (type, source) pairs
	 * ever active; a hit looks up with the entry's own objects and allocates nothing (per-tick callers: flight, air
	 * jump, detonate, frost walk, sink). Concurrent maps only as a guard: the server thread is the one writer.
	 */
	private static final Map<BehaviorType<?>, Map<Identifier, EntryKeys[]>> KEYS = new ConcurrentHashMap<>();

	/**
	 * The per-player key of one active entry: {@code absorbaholic:<type path>/<source namespace>/<source path>}, plus
	 * {@code /weakness} on the weakness side (a source may use the same type on both sides, e.g. chorus teleport).
	 * Built once per (type, source, side) and cached: the same instance every call.
	 */
	public static Identifier key(ActiveBehavior<?> self) {
		return keys(self).base;
	}

	/** {@link #key} plus {@code /<suffix>}, for secondary timers of the same entry (cached like the key). */
	public static Identifier key(ActiveBehavior<?> self, String suffix) {
		EntryKeys keys = keys(self);
		Identifier cached = keys.suffixed.get(suffix);
		if (cached != null) return cached;
		Identifier created = Absorbaholic.id(keys.base.getPath() + "/" + suffix);
		Identifier raced = keys.suffixed.putIfAbsent(suffix, created);
		return raced != null ? raced : created;
	}

	private static EntryKeys keys(ActiveBehavior<?> self) {
		Map<Identifier, EntryKeys[]> bySource = KEYS.get(self.type());
		if (bySource == null) {
			Map<Identifier, EntryKeys[]> created = new ConcurrentHashMap<>();
			Map<Identifier, EntryKeys[]> raced = KEYS.putIfAbsent(self.type(), created);
			bySource = raced != null ? raced : created;
		}
		Identifier source = self.sourceId();
		EntryKeys[] sides = bySource.get(source);
		if (sides == null) {
			EntryKeys[] created = new EntryKeys[2];
			EntryKeys[] raced = bySource.putIfAbsent(source, created);
			sides = raced != null ? raced : created;
		}
		int side = self.weakness() ? 1 : 0;
		EntryKeys keys = sides[side];
		if (keys == null) {
			keys = new EntryKeys(Absorbaholic.id(self.type().id().getPath() + "/" + source.getNamespace() + "/" + source.getPath()
					+ (self.weakness() ? "/weakness" : "")));
			sides[side] = keys;
		}
		return keys;
	}

	/** The cached keys of one (type, source, side): the base key and its suffixed variants. */
	private static final class EntryKeys {
		final Identifier base;
		final Map<String, Identifier> suffixed = new ConcurrentHashMap<>(4);

		EntryKeys(Identifier base) {
			this.base = base;
		}
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

	/**
	 * A player ability's explosion (sneak_detonate, player fireballs): never fire, never blocks
	 * ({@code ExplosionInteraction.NONE} and a calculator that refuses every block), and {@link #sparedByBlast} entities
	 * take neither damage nor knockback. {@code owner} may be null (e.g. the shooter logged out): then no player is hit.
	 */
	public static void explode(ServerLevel level, @Nullable Entity source, DamageSource damage, @Nullable ServerPlayer owner, double x, double y, double z,
			float power) {
		level.explode(source, damage, new PlayerBlastCalculator(owner), x, y, z, power, false, Level.ExplosionInteraction.NONE);
	}

	/**
	 * Entities a player's ability blast leaves alone (no damage, no knockback): other players the owner may not harm
	 * (PvP off, teams; all players when there is no owner), owned or tamed mobs, villagers and wandering traders, and
	 * "things": armor stands, mannequins, item frames / paintings / leash knots, display entities, items, experience
	 * orbs and vehicles. Hostile mobs, untamed animals and harmable players are hit as by any explosion. The owner is
	 * never spared by this rule (a sneak_detonate blast excludes its source anyway).
	 */
	public static boolean sparedByBlast(@Nullable ServerPlayer owner, Entity entity) {
		if (entity == owner) return false;
		if (entity instanceof Player other) return owner == null || !owner.canHarmPlayer(other);
		if (entity instanceof OwnableEntity ownable && ownable.getOwnerReference() != null) return true;
		return entity instanceof AbstractVillager || entity instanceof ArmorStand || entity instanceof Mannequin || entity instanceof BlockAttachedEntity
				|| entity instanceof Display || entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof VehicleEntity;
	}

	/** Damage calculator of {@link #explode}: spared entities are neither hurt nor pushed; no block is ever affected. */
	static final class PlayerBlastCalculator extends ExplosionDamageCalculator {
		private final @Nullable ServerPlayer owner;

		PlayerBlastCalculator(@Nullable ServerPlayer owner) {
			this.owner = owner;
		}

		@Override
		public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, float power) {
			return false;
		}

		@Override
		public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
			return !sparedByBlast(owner, entity);
		}

		@Override
		public float getKnockbackMultiplier(Entity entity) {
			return sparedByBlast(owner, entity) ? 0.0F : 1.0F;
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
