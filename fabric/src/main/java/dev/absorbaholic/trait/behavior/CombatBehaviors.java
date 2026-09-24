package dev.absorbaholic.trait.behavior;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.BehaviorType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * WP-BEH-A. Behavior group: damage / immunity / on-hit / thorns / kill rewards (incoming, outgoing, dealt, attacked,
 * kill and projectile-hit hooks): {@code damage_dealt_multiplier}, {@code attack_effect}, {@code retaliate},
 * {@code kill_reward}, {@code struck_by} ({@code damage_multiplier} registers itself, see BuiltinBehaviors). Also holds
 * the small helpers these behaviors share: the effect payload fields, per-entry cooldowns and param validation.
 */
public final class CombatBehaviors {
	/** Every type of this group, in registration order. */
	public static final List<BehaviorType<?>> TYPES = List.of(
			DamageDealtMultiplierBehavior.TYPE,
			AttackEffectBehavior.TYPE,
			RetaliateBehavior.TYPE,
			KillRewardBehavior.TYPE,
			StruckByBehavior.TYPE);

	private CombatBehaviors() {}

	/** Registers the group (touching {@link #TYPES} initializes, and so registers, every type). */
	public static void register() {
		Absorbaholic.LOGGER.debug("Registered {} combat behavior types", TYPES.size());
	}

	/** Server tick counter used for cooldowns (the same clock as the engine and the damage gate). */
	static long now(ServerPlayer player) {
		return player.level().getServer().getTickCount();
	}

	/**
	 * Key of an entry's per-player scratch state in {@code PlayerRuntime.behaviorState}:
	 * {@code absorbaholic:<type>/<source namespace>/<source path>/<trait|weakness>}.
	 */
	static Identifier stateKey(ActiveBehavior<?> self) {
		return Absorbaholic.id(self.type().id().getPath() + "/" + self.sourceId().getNamespace() + "/" + self.sourceId().getPath()
				+ (self.weakness() ? "/weakness" : "/trait"));
	}

	/**
	 * Per-entry rate limit: true (and the next {@code ticks} ticks are blocked) if the entry is ready, false while it is
	 * still cooling down.
	 */
	static boolean tryCooldown(ServerPlayer player, ActiveBehavior<?> self, int ticks) {
		long now = now(player);
		Map<Identifier, Object> state = PlayerData.runtime(player).behaviorState;
		Identifier key = stateKey(self);
		if (state.get(key) instanceof long[] until) {
			if (now < until[0]) return false;
			until[0] = now + ticks;
		} else {
			state.put(key, new long[] {now + ticks});
		}
		return true;
	}

	/** Per-entry, per-entity rate limit (retaliate): like {@link #tryCooldown}, tracked separately for every entity. */
	@SuppressWarnings("unchecked")
	static boolean tryCooldown(ServerPlayer player, ActiveBehavior<?> self, Entity other, int ticks) {
		long now = now(player);
		Map<Identifier, Object> state = PlayerData.runtime(player).behaviorState;
		Map<UUID, Long> until = (Map<UUID, Long>) state.computeIfAbsent(stateKey(self), k -> new HashMap<UUID, Long>());
		Long end = until.get(other.getUUID());
		if (end != null && now < end) return false;
		if (until.size() >= 32) until.values().removeIf(t -> t <= now); // stale attackers
		until.put(other.getUUID(), now + ticks);
		return true;
	}

	/** Rolls {@code chance} (at the entry's level); 1 or more always passes, 0 or less never (disable sentinel). */
	static boolean roll(ServerPlayer player, LevelValue chance, int level) {
		double c = chance.at(level);
		return c >= 1.0 || c > 0.0 && player.getRandom().nextDouble() < c;
	}

	/** Every level a source may have (1..MAX_MAX_LEVEL) has a finite value &gt;= {@code min}. */
	static DataResult<LevelValue> atLeast(LevelValue value, double min, String name) {
		for (int level = 1; level <= AbsorbCaps.MAX_MAX_LEVEL; level++) {
			double v = value.at(level);
			if (!Double.isFinite(v) || v < min) {
				int l = level;
				return DataResult.error(() -> name + " must be >= " + min + " (level " + l + ": " + v + ")");
			}
		}
		return DataResult.success(value);
	}

	/**
	 * The optional effect part of a payload: {@code "effect"} (a mob effect id), {@code "amplifier"} [L] (0-based;
	 * &lt; 0 disables the effect at that level; default 0) and {@code "duration"} [L] (ticks; &lt;= 0 disables; default
	 * 100). Embed {@link #FIELDS} in a params codec.
	 */
	public record EffectPayload(Optional<Holder<MobEffect>> effect, LevelValue amplifier, LevelValue duration) {
		public static final EffectPayload NONE = new EffectPayload(Optional.empty(), LevelValue.constant(0), LevelValue.constant(100));

		public static final MapCodec<EffectPayload> FIELDS = RecordCodecBuilder.mapCodec(i -> i.group(
				BuiltInRegistries.MOB_EFFECT.holderByNameCodec().optionalFieldOf("effect").forGetter(EffectPayload::effect),
				LevelValue.CODEC.optionalFieldOf("amplifier", LevelValue.constant(0)).forGetter(EffectPayload::amplifier),
				LevelValue.CODEC.optionalFieldOf("duration", LevelValue.constant(100)).forGetter(EffectPayload::duration)
		).apply(i, EffectPayload::new));

		public boolean isPresent() {
			return effect.isPresent();
		}

		/**
		 * Adds the effect to {@code target} at {@code level} (credited to {@code source}), unless it is disabled at that
		 * level. The amplifier never exceeds {@code AbsorbCaps.EFFECT_AMPLIFIER_MAX}. Returns true if vanilla accepted it.
		 */
		public boolean apply(LivingEntity target, @Nullable Entity source, int level) {
			if (effect.isEmpty()) return false;
			int amp = amplifier.atInt(level);
			int ticks = duration.atInt(level);
			if (amp < 0 || ticks <= 0) return false;
			return target.addEffect(new MobEffectInstance(effect.get(), ticks, Math.min(amp, AbsorbCaps.EFFECT_AMPLIFIER_MAX)), source);
		}
	}

	/** Sets {@code target} on fire for at least {@code seconds} (never shortens a longer burn); &lt;= 0 does nothing. */
	static void ignite(Entity target, double seconds) {
		if (seconds > 0.0 && Double.isFinite(seconds)) target.igniteForTicks((int) Math.min(Math.round(seconds * 20.0), Integer.MAX_VALUE));
	}
}
