package dev.absorbaholic.trait.behavior;

import java.util.Optional;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.behavior.CombatBehaviors.EffectPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code absorbaholic:attack_effect}: after the player hurt a target, with probability {@code chance} [L], apply the
 * payload to it: {@code effect} + {@code amplifier} [L] + {@code duration} [L] and / or {@code ignite_seconds} [L] (at
 * least one is required). Without {@code damage_tag} it fires only for the player's own melee
 * ({@code minecraft:player_attack} with the player as direct entity); with a tag (e.g. {@code minecraft:is_projectile})
 * it fires for matching damage the player caused, such as its arrows, tridents and snowballs. Only when the target
 * survived and actually lost health. Shared condition fields are evaluated at hit time.
 *
 * <pre>{"type": "absorbaholic:attack_effect", "damage_tag": "minecraft:is_projectile", "effect": "minecraft:poison",
 * "amplifier": [0, 0, 0], "duration": [60, 80, 120], "chance": [1.0, 1.0, 1.0]}</pre>
 */
public final class AttackEffectBehavior implements Behavior<AttackEffectBehavior.Params> {
	public record Params(LevelValue chance, EffectPayload payload, Optional<LevelValue> igniteSeconds, Optional<TagKey<DamageType>> damageTag,
			Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("chance").forGetter(Params::chance),
				EffectPayload.FIELDS.forGetter(Params::payload),
				LevelValue.CODEC.optionalFieldOf("ignite_seconds").forGetter(Params::igniteSeconds),
				TagKey.codec(Registries.DAMAGE_TYPE).optionalFieldOf("damage_tag").forGetter(Params::damageTag),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			return payload.isPresent() || igniteSeconds.isPresent()
					? DataResult.success(this)
					: DataResult.error(() -> "attack_effect needs \"effect\" or \"ignite_seconds\"");
		}

		/** The hit counts: the tagged damage, or (no tag) the player's own melee attack. */
		boolean counts(ServerPlayer player, DamageSource source) {
			if (damageTag.isPresent()) return source.is(damageTag.get());
			return source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() == player;
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("attack_effect", Params.CODEC, new AttackEffectBehavior());

	@Override
	public void onDealtDamage(ActiveBehavior<Params> self, ServerPlayer player, LivingEntity target, DamageSource source, float amount) {
		Params p = self.params();
		if (!(amount > 0.0F) || !target.isAlive() || !p.counts(player, source) || !p.condition().test(player)) return;
		if (!CombatBehaviors.roll(player, p.chance(), self.level())) return;
		p.payload().apply(target, player, self.level());
		p.igniteSeconds().ifPresent(s -> CombatBehaviors.ignite(target, s.at(self.level())));
	}
}
