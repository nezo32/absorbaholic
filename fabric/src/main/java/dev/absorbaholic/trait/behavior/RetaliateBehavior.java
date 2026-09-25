package dev.absorbaholic.trait.behavior;

import java.util.Optional;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.WeaknessDamage;
import dev.absorbaholic.trait.behavior.CombatBehaviors.EffectPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code absorbaholic:retaliate} (thorns): after a living attacker hurt the player in direct melee
 * ({@code source.getDirectEntity() == source.getEntity()}, within {@code AbsorbCaps.RETALIATE_MAX_RANGE} blocks), with
 * probability {@code chance} [L], hit back with any of: {@code damage} [L] (vanilla {@code minecraft:thorns} damage
 * credited to the player), {@code effect} + {@code amplifier} [L] + {@code duration} [L], {@code ignite_seconds} [L].
 * Projectiles never trigger it, nor does thorns or weakness damage, nor a hit an immunity cancelled (no damage event).
 * Per attacker at most once per {@code AbsorbCaps.RETALIATE_COOLDOWN_TICKS}. Shared condition fields at hit time.
 *
 * <pre>{"type": "absorbaholic:retaliate", "damage": [1.0, 1.5, 2.0], "chance": [0.5, 0.75, 1.0]}</pre>
 */
public final class RetaliateBehavior implements Behavior<RetaliateBehavior.Params> {
	public record Params(LevelValue chance, Optional<LevelValue> damage, EffectPayload payload, Optional<LevelValue> igniteSeconds,
			Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("chance").forGetter(Params::chance),
				LevelValue.CODEC.optionalFieldOf("damage").forGetter(Params::damage),
				EffectPayload.FIELDS.forGetter(Params::payload),
				LevelValue.CODEC.optionalFieldOf("ignite_seconds").forGetter(Params::igniteSeconds),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			return damage.isPresent() || payload.isPresent() || igniteSeconds.isPresent()
					? DataResult.success(this)
					: DataResult.error(() -> "retaliate needs \"damage\", \"effect\" or \"ignite_seconds\"");
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("retaliate", Params.CODEC, new RetaliateBehavior());

	private static final double MAX_RANGE_SQR = AbsorbCaps.RETALIATE_MAX_RANGE * AbsorbCaps.RETALIATE_MAX_RANGE;

	@Override
	public void onAttacked(ActiveBehavior<Params> self, ServerPlayer player, DamageSource source, float amount) {
		if (!(amount > 0.0F) || source.is(DamageTypes.THORNS) || source.is(WeaknessDamage.TYPE)) return;
		if (!(source.getEntity() instanceof LivingEntity attacker) || source.getDirectEntity() != attacker) return;
		if (attacker == player || !attacker.isAlive() || player.distanceToSqr(attacker) > MAX_RANGE_SQR) return;
		Params p = self.params();
		int level = self.level();
		if (!p.condition().test(player)) return;
		if (!CombatBehaviors.roll(player, p.chance(), level)) return;
		// the cooldown starts before hitting back, so two retaliating players can never ping-pong
		if (!CombatBehaviors.tryCooldown(player, self, attacker, AbsorbCaps.RETALIATE_COOLDOWN_TICKS)) return;
		double damage = p.damage().map(d -> d.at(level)).orElse(0.0);
		if (damage > 0.0) attacker.hurtServer(player.level(), player.damageSources().thorns(player), (float) damage);
		if (!attacker.isAlive()) return;
		p.payload().apply(attacker, player, level);
		p.igniteSeconds().ifPresent(s -> CombatBehaviors.ignite(attacker, s.at(level)));
	}
}
