package dev.absorbaholic.trait.behavior;

import java.util.List;
import java.util.Optional;

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
import dev.absorbaholic.trait.TargetFilter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/**
 * {@code absorbaholic:damage_multiplier}: damage taken by the player is multiplied by {@code multiplier} at the
 * entry's level when every given filter matches: {@code damage_tag} (a damage type tag), {@code damage_types} (damage
 * type ids), {@code attacker} (entity filter on the causing entity), and the shared condition fields. No filter =
 * all damage except #bypasses_invulnerability and absorbaholic:weakness. On a trait use values &lt; 1 (engine floor
 * 0.25); on a weakness values &gt; 1 (the extra goes through the weakness damage gate). A multiplier &lt;= 0 with a damage-type filter is a specific-type immunity (never for
 * /kill, void or generic damage). Reference implementation of the behavior pattern.
 *
 * <pre>{"type": "absorbaholic:damage_multiplier", "damage_tag": "minecraft:is_explosion", "multiplier": [0.75, 0.5, 0.3]}</pre>
 */
public final class DamageMultiplierBehavior implements Behavior<DamageMultiplierBehavior.Params> {
	public record Params(Optional<TagKey<DamageType>> damageTag, List<ResourceKey<DamageType>> damageTypes, TargetFilter attacker,
			Condition condition, LevelValue multiplier) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				TagKey.codec(Registries.DAMAGE_TYPE).optionalFieldOf("damage_tag").forGetter(Params::damageTag),
				ResourceKey.codec(Registries.DAMAGE_TYPE).listOf().optionalFieldOf("damage_types", List.of()).forGetter(Params::damageTypes),
				TargetFilter.CODEC.optionalFieldOf("attacker", TargetFilter.ANY).forGetter(Params::attacker),
				Condition.FIELDS.forGetter(Params::condition),
				LevelValue.CODEC.fieldOf("multiplier").forGetter(Params::multiplier)
		).apply(i, Params::new)).validate(Params::validate);

		/** A 0 (immunity) at any level is only allowed with a filter naming allowed immunity tags / types only. */
		private DataResult<Params> validate() {
			boolean immunity = false;
			for (int level = 1; level <= AbsorbCaps.MAX_MAX_LEVEL; level++) {
				if (multiplier.at(level) <= 0.0) immunity = true;
			}
			if (!immunity) return DataResult.success(this);
			if (!typeSpecific()) return DataResult.error(() -> "multiplier 0 (immunity) needs damage_tag or damage_types");
			if (damageTag.isPresent() && !AbsorbCaps.IMMUNITY_TAGS.contains(damageTag.get().location().toString())) {
				return DataResult.error(() -> "immunity not allowed for tag " + damageTag.get().location());
			}
			for (ResourceKey<DamageType> t : damageTypes) {
				if (!AbsorbCaps.IMMUNITY_TYPES.contains(t.identifier().toString())) {
					return DataResult.error(() -> "immunity not allowed for damage type " + t.identifier());
				}
			}
			return DataResult.success(this);
		}

		/** A damage-type filter is present (required for an immunity). */
		boolean typeSpecific() {
			return damageTag.isPresent() || !damageTypes.isEmpty();
		}

		boolean matches(ServerPlayer player, DamageSource source) {
			if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || source.is(WEAKNESS)) return false;
			if (damageTag.isPresent() && !source.is(damageTag.get())) return false;
			if (!damageTypes.isEmpty() && damageTypes.stream().noneMatch(source::is)) return false;
			if (!attacker.isAny() && (source.getEntity() == null || !attacker.test(source.getEntity()))) return false;
			return condition.test(player);
		}
	}

	private static final ResourceKey<DamageType> WEAKNESS = ResourceKey.create(Registries.DAMAGE_TYPE, Absorbaholic.id("weakness"));

	public static final BehaviorType<Params> TYPE =
			BehaviorRegistry.register("damage_multiplier", Params.CODEC, new DamageMultiplierBehavior());

	@Override
	public boolean isImmuneTo(ActiveBehavior<Params> self, ServerPlayer player, DamageSource source) {
		Params p = self.params();
		return p.typeSpecific() && p.multiplier().at(self.level()) <= 0.0 && p.matches(player, source);
	}

	@Override
	public float incomingDamageFactor(ActiveBehavior<Params> self, ServerPlayer player, DamageSource source, float amount) {
		return self.params().matches(player, source) ? (float) self.params().multiplier().at(self.level()) : 1.0F;
	}
}
