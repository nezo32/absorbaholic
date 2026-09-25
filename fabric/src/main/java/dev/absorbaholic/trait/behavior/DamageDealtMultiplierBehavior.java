package dev.absorbaholic.trait.behavior;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code absorbaholic:damage_dealt_multiplier}: damage the player deals (any damage whose attacker is the player, so
 * projectiles count through their owner) is multiplied by {@code multiplier} [L] when every given filter matches:
 * {@code damage_tag} (a damage type tag, no {@code #}), {@code damage_types} (damage type ids), {@code target} (entity
 * filter on the victim) and the shared condition fields (evaluated at hit time). The engine clamps the combined
 * factor of all instances to {@code AbsorbCaps.DAMAGE_DEALT_MIN..MAX} (0.5..2).
 *
 * <pre>{"type": "absorbaholic:damage_dealt_multiplier", "damage_tag": "minecraft:is_projectile", "multiplier": [1.15, 1.3, 1.5]}</pre>
 */
public final class DamageDealtMultiplierBehavior implements Behavior<DamageDealtMultiplierBehavior.Params> {
	public record Params(LevelValue multiplier, Optional<TagKey<DamageType>> damageTag, List<ResourceKey<DamageType>> damageTypes,
			TargetFilter target, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("multiplier").validate(v -> CombatBehaviors.atLeast(v, 0.0, "multiplier")).forGetter(Params::multiplier),
				TagKey.codec(Registries.DAMAGE_TYPE).optionalFieldOf("damage_tag").forGetter(Params::damageTag),
				ResourceKey.codec(Registries.DAMAGE_TYPE).listOf().optionalFieldOf("damage_types", List.of()).forGetter(Params::damageTypes),
				TargetFilter.CODEC.optionalFieldOf("target", TargetFilter.ANY).forGetter(Params::target),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));

		boolean matches(ServerPlayer player, LivingEntity victim, DamageSource source) {
			if (damageTag.isPresent() && !source.is(damageTag.get())) return false;
			if (!damageTypes.isEmpty() && damageTypes.stream().noneMatch(source::is)) return false;
			if (!target.test(victim)) return false;
			return condition.test(player);
		}
	}

	public static final BehaviorType<Params> TYPE =
			BehaviorRegistry.register("damage_dealt_multiplier", Params.CODEC, new DamageDealtMultiplierBehavior());

	@Override
	public float outgoingDamageFactor(ActiveBehavior<Params> self, ServerPlayer player, LivingEntity target, DamageSource source, float amount) {
		return self.params().matches(player, target, source) ? (float) self.params().multiplier().at(self.level()) : 1.0F;
	}
}
