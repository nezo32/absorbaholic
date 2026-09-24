package dev.absorbaholic.trait.behavior;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.Hook;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.trait.WeaknessDamage;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * {@code absorbaholic:effect_modifier}: changes effects applied to the player. Targets: {@code effects} (ids) or
 * {@code category} ({@code beneficial|harmful|neutral}). {@code mode}:
 * <ul>
 * <li>{@code immune}: the effect is not applied ({@code value} unused);</li>
 * <li>{@code duration}: duration × {@code value} (rounded, at least 1 tick); a value &lt;= 0 denies the effect;
 *     instant effects are unchanged;</li>
 * <li>{@code amplifier}: amplifier + {@code value}, clamped to 0..4 (never lowers an amplifier above 4 that it did not
 *     change);</li>
 * <li>{@code invert}: instant health acts as instant damage of the same amplifier and vice versa, like undead mobs
 *     ({@code effects} may list only {@code instant_health} / {@code instant_damage}); the damage is weakness damage
 *     and goes through the gate ({@code HealOrHarmMobEffectMixin} → {@link #invertInstant}).</li>
 * </ul>
 * Never touches effects our {@code status_effect} applied (the engine skips owned effects), ambient effects from
 * beacons / conduits, or effects with infinite duration. Several {@code duration} entries multiply.
 *
 * <pre>{"type": "absorbaholic:effect_modifier", "effects": ["minecraft:poison"], "mode": "duration", "value": [0.75, 0.5, 0.25]}</pre>
 */
public final class EffectModifierBehavior implements Behavior<EffectModifierBehavior.Params> {
	/** What the entry does to a matching effect. */
	public enum Mode implements StringRepresentable {
		IMMUNE("immune"),
		DURATION("duration"),
		AMPLIFIER("amplifier"),
		INVERT("invert");

		public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
		private final String name;

		Mode(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	/** {@link MobEffectCategory} by its lower-case name. */
	public enum Category implements StringRepresentable {
		BENEFICIAL("beneficial", MobEffectCategory.BENEFICIAL),
		HARMFUL("harmful", MobEffectCategory.HARMFUL),
		NEUTRAL("neutral", MobEffectCategory.NEUTRAL);

		public static final Codec<Category> CODEC = StringRepresentable.fromEnum(Category::values);
		private final String name;
		final MobEffectCategory category;

		Category(String name, MobEffectCategory category) {
			this.name = name;
			this.category = category;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public record Params(Optional<List<Holder<MobEffect>>> effects, Optional<Category> category, Mode mode, LevelValue value, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				MobEffect.CODEC.listOf().optionalFieldOf("effects").forGetter(Params::effects),
				Category.CODEC.optionalFieldOf("category").forGetter(Params::category),
				Mode.CODEC.fieldOf("mode").forGetter(Params::mode),
				LevelValue.CODEC.optionalFieldOf("value", LevelValue.constant(1.0)).forGetter(Params::value),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			if (effects.isPresent() == category.isPresent()) return DataResult.error(() -> "needs exactly one of \"effects\" or \"category\"");
			if (effects.isPresent() && effects.get().isEmpty()) return DataResult.error(() -> "\"effects\" must not be empty");
			if (mode == Mode.INVERT) {
				if (effects.isEmpty()) return DataResult.error(() -> "mode invert needs \"effects\"");
				for (Holder<MobEffect> e : effects.get()) {
					if (e.value() != MobEffects.INSTANT_HEALTH.value() && e.value() != MobEffects.INSTANT_DAMAGE.value()) {
						return DataResult.error(() -> "mode invert only works for instant_health and instant_damage");
					}
				}
			}
			return DataResult.success(this);
		}

		boolean targets(Holder<MobEffect> effect) {
			if (effects.isPresent()) {
				for (Holder<MobEffect> e : effects.get()) {
					if (e.value() == effect.value()) return true;
				}
				return false;
			}
			return category.isPresent() && effect.value().getCategory() == category.get().category;
		}

		/** A matching effect instance we may touch (not ambient, not infinite). */
		boolean matches(MobEffectInstance instance) {
			return !instance.isAmbient() && !instance.isInfiniteDuration() && targets(instance.getEffect());
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("effect_modifier", Params.CODEC, new EffectModifierBehavior());

	private static boolean warnedInvert;

	@Override
	public boolean allowEffect(ActiveBehavior<Params> self, ServerPlayer player, MobEffectInstance effect) {
		Params p = self.params();
		boolean deny = switch (p.mode()) {
			case IMMUNE -> true;
			case DURATION -> !effect.getEffect().value().isInstantaneous() && !(p.value().at(self.level()) > 0.0);
			default -> false;
		};
		return !(deny && p.matches(effect) && EntryStates.condition(player, self, p.condition()));
	}

	@Override
	public MobEffectInstance modifyEffect(ActiveBehavior<Params> self, ServerPlayer player, MobEffectInstance effect) {
		Params p = self.params();
		if (p.mode() != Mode.AMPLIFIER && p.mode() != Mode.DURATION) return effect;
		if (!p.matches(effect) || !EntryStates.condition(player, self, p.condition())) return effect;
		double value = p.value().at(self.level());
		int amplifier = effect.getAmplifier();
		int duration = effect.getDuration();
		if (p.mode() == Mode.AMPLIFIER) {
			int add = (int) Math.round(value);
			if (add == 0) return effect;
			amplifier = Mth.clamp(amplifier + add, 0, Math.max(amplifier, AbsorbCaps.EFFECT_AMPLIFIER_MAX));
		} else {
			if (effect.getEffect().value().isInstantaneous() || !(value > 0.0)) return effect;
			duration = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, Math.round(duration * value)));
		}
		if (amplifier == effect.getAmplifier() && duration == effect.getDuration()) return effect;
		return new MobEffectInstance(effect.getEffect(), duration, amplifier, effect.isAmbient(), effect.isVisible(), effect.showIcon());
	}

	/**
	 * {@code HealOrHarmMobEffectMixin}: an instant health ({@code harm = false}) or instant damage effect is about to act
	 * on {@code player}. If an active {@code invert} entry covers it, does the opposite (damage through the weakness
	 * gate / healing) and returns true (vanilla is skipped). Never throws.
	 */
	public static boolean invertInstant(ServerPlayer player, boolean harm, int amplifier, double scale, @Nullable Entity owner) {
		try {
			if (!TraitEngine.isActive(player)) return false;
			Holder<MobEffect> effect = harm ? MobEffects.INSTANT_DAMAGE : MobEffects.INSTANT_HEALTH;
			for (ActiveBehavior<?> a : TraitEngine.active(player).forHook(Hook.MODIFY_EFFECT)) {
				if (a.type() != TYPE) continue;
				@SuppressWarnings("unchecked")
				ActiveBehavior<Params> self = (ActiveBehavior<Params>) a;
				Params p = self.params();
				if (p.mode() != Mode.INVERT || !p.targets(effect) || !EntryStates.condition(player, self, p.condition())) continue;
				int shift = Mth.clamp(amplifier, 0, 30);
				if (harm) {
					player.heal((int) (scale * Math.max(4 << shift, 0) + 0.5));
				} else {
					WeaknessDamage.hurt(player, (int) (scale * (6 << shift) + 0.5), owner == player ? null : owner);
				}
				return true;
			}
		} catch (RuntimeException e) {
			if (!warnedInvert) {
				warnedInvert = true;
				Absorbaholic.LOGGER.error("Absorbaholic effect_modifier invert failed", e);
			}
		}
		return false;
	}
}
