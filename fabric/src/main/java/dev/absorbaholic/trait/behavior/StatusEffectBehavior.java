package dev.absorbaholic.trait.behavior;

import java.util.Optional;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerRuntime;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.Hook;
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.jspecify.annotations.Nullable;

/**
 * {@code absorbaholic:status_effect}: keeps a vanilla effect on the player while the condition holds.
 * <ul>
 * <li>Permanent mode (no {@code interval}): every 20 ticks, while the condition holds, the player has {@code effect}
 *     at {@code amplifier} with at least 60 ticks left (night vision: 220, so it never flickers); applied through
 *     {@link TraitEngine#addOwnedEffect} as {@code ambient, no particles, icon}. When the condition turns false or the
 *     entry goes away, the instance is removed only if it is still ours (same amplifier and flags, owned by this
 *     source); another active entry that wants the same effect takes it over instead.</li>
 * <li>Pulse mode ({@code interval} + {@code duration}): every {@code interval} ticks while the condition holds, the
 *     effect is applied for {@code duration} ticks (the first pulse comes one interval after the entry became active).</li>
 * </ul>
 * An amplifier &lt; 0 disables the entry at that level. Owned effects bypass effect immunities and are never changed by
 * {@code effect_modifier} (behaviors.md §0.5).
 *
 * <pre>{"type": "absorbaholic:status_effect", "effect": "minecraft:speed", "amplifier": [-1, 0, 1]}</pre>
 */
public final class StatusEffectBehavior implements Behavior<StatusEffectBehavior.Params> {
	/** Permanent mode: applied duration and refresh threshold; night vision flickers on the client below 200 ticks. */
	static final int PERMANENT_DURATION = AbsorbCaps.EFFECT_PERMANENT_DURATION_TICKS;
	static final int PERMANENT_MIN_LEFT = AbsorbCaps.EFFECT_PERMANENT_MIN_LEFT_TICKS;
	static final int NIGHT_VISION_DURATION = AbsorbCaps.EFFECT_NIGHT_VISION_DURATION_TICKS;
	static final int NIGHT_VISION_MIN_LEFT = AbsorbCaps.EFFECT_NIGHT_VISION_MIN_LEFT_TICKS;
	static final int PERMANENT_INTERVAL = AbsorbCaps.EFFECT_PERMANENT_INTERVAL_TICKS;
	static final int PULSE_INTERVAL = AbsorbCaps.EFFECT_PULSE_INTERVAL_TICKS;

	public record Params(Holder<MobEffect> effect, LevelValue amplifier, Optional<LevelValue> interval, Optional<LevelValue> duration,
			Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				MobEffect.CODEC.fieldOf("effect").forGetter(Params::effect),
				LevelValue.CODEC.fieldOf("amplifier").forGetter(Params::amplifier),
				LevelValue.CODEC.optionalFieldOf("interval").forGetter(Params::interval),
				LevelValue.CODEC.optionalFieldOf("duration").forGetter(Params::duration),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			if (interval.isPresent() != duration.isPresent()) {
				return DataResult.error(() -> "pulse mode needs both \"interval\" and \"duration\"");
			}
			return DataResult.success(this);
		}

		boolean pulse() {
			return interval.isPresent();
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("status_effect", Params.CODEC, new StatusEffectBehavior());

	@Override
	public int tickInterval(Params p) {
		return p.pulse() ? PULSE_INTERVAL : PERMANENT_INTERVAL;
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		int amplifier = p.amplifier().atInt(self.level());
		if (amplifier < 0) return;
		boolean holds = EntryStates.condition(player, self, p.condition());
		if (p.pulse()) {
			pulse(self, player, amplifier, holds);
		} else if (holds) {
			ensure(self, player, amplifier);
		} else {
			release(self, player, amplifier, true);
		}
	}

	@Override
	public void onDeactivate(ActiveBehavior<Params> self, ServerPlayer player) {
		int amplifier = self.params().amplifier().atInt(self.level());
		if (amplifier >= 0) release(self, player, amplifier, false);
		EntryStates.remove(player, self);
	}

	private static void pulse(ActiveBehavior<Params> self, ServerPlayer player, int amplifier, boolean holds) {
		Params p = self.params();
		EntryStates.State state = EntryStates.get(player, self);
		long now = EntryStates.now(player);
		if (state.lastPulse == Long.MIN_VALUE) state.lastPulse = now;
		int interval = Math.max(1, p.interval().orElseThrow().atInt(self.level()));
		int duration = p.duration().orElseThrow().atInt(self.level());
		if (!holds || duration <= 0 || now - state.lastPulse < interval) return;
		state.lastPulse = now;
		TraitEngine.addOwnedEffect(player, instance(p.effect(), duration, amplifier), self.sourceId());
	}

	/** The player has the effect at {@code amplifier} (or stronger) with enough time left. */
	private static void ensure(ActiveBehavior<Params> self, ServerPlayer player, int amplifier) {
		Holder<MobEffect> effect = self.params().effect();
		boolean nightVision = effect.value() == MobEffects.NIGHT_VISION.value();
		MobEffectInstance current = player.getEffect(effect);
		if (current == null || current.getAmplifier() < amplifier
				|| current.getAmplifier() == amplifier && !current.isInfiniteDuration()
						&& current.getDuration() < (nightVision ? NIGHT_VISION_MIN_LEFT : PERMANENT_MIN_LEFT)) {
			TraitEngine.addOwnedEffect(player, instance(effect, nightVision ? NIGHT_VISION_DURATION : PERMANENT_DURATION, amplifier), self.sourceId());
		}
	}

	/**
	 * Removes our instance of the effect (never a potion, a beacon or another source's instance). With
	 * {@code handOver}, another active entry of this type that currently wants the same effect keeps it (the effect is
	 * re-applied at that entry's amplifier and owned by its source).
	 */
	private static void release(ActiveBehavior<Params> self, ServerPlayer player, int amplifier, boolean handOver) {
		Holder<MobEffect> effect = self.params().effect();
		MobEffectInstance current = player.getEffect(effect);
		Identifier id = effectId(effect);
		if (current == null || id == null || current.getAmplifier() != amplifier || !TraitEngine.isOwnedEffect(player, current)) return;
		PlayerRuntime rt = PlayerData.runtime(player);
		if (!self.sourceId().equals(rt.ownedEffects.get(id))) return;
		ActiveBehavior<Params> heir = handOver ? heir(self, player, effect) : null;
		if (heir != null && heir.params().amplifier().atInt(heir.level()) == amplifier) {
			rt.ownedEffects.put(id, heir.sourceId());
			return;
		}
		player.removeEffect(effect);
		rt.ownedEffects.remove(id);
		if (heir != null) ensure(heir, player, heir.params().amplifier().atInt(heir.level()));
	}

	/** Another active permanent entry of this type for the same effect whose condition holds (highest amplifier). */
	@SuppressWarnings("unchecked")
	private static @Nullable ActiveBehavior<Params> heir(ActiveBehavior<Params> self, ServerPlayer player, Holder<MobEffect> effect) {
		ActiveBehavior<Params> best = null;
		int bestAmplifier = -1;
		for (ActiveBehavior<?> a : TraitEngine.active(player).forHook(Hook.TICK)) {
			if (a == self || a.equals(self) || a.type() != TYPE) continue;
			ActiveBehavior<Params> other = (ActiveBehavior<Params>) a;
			Params op = other.params();
			int amplifier = op.amplifier().atInt(other.level());
			if (op.pulse() || op.effect().value() != effect.value() || amplifier <= bestAmplifier) continue;
			if (!EntryStates.condition(player, other, op.condition())) continue;
			best = other;
			bestAmplifier = amplifier;
		}
		return best;
	}

	private static MobEffectInstance instance(Holder<MobEffect> effect, int duration, int amplifier) {
		return new MobEffectInstance(effect, duration, amplifier, true, false, true);
	}

	private static @Nullable Identifier effectId(Holder<MobEffect> effect) {
		return effect.unwrapKey().map(ResourceKey::identifier).orElse(null);
	}
}
