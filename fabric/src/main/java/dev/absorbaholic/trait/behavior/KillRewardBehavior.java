package dev.absorbaholic.trait.behavior;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.behavior.CombatBehaviors.EffectPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code absorbaholic:kill_reward}: when the player kills a living entity whose max health is at least
 * {@code AbsorbCaps.KILL_REWARD_MIN_VICTIM_MAX_HEALTH} (no farming baby chicks or fish), heal {@code heal} [L] HP
 * (through {@code heal_multiplier}) and optionally gain {@code effect} + {@code amplifier} [L] + {@code duration} [L].
 * At most one reward per {@code AbsorbCaps.KILL_REWARD_COOLDOWN_TICKS}. Shared condition fields at kill time.
 *
 * <pre>{"type": "absorbaholic:kill_reward", "heal": [1, 2, 3]}</pre>
 */
public final class KillRewardBehavior implements Behavior<KillRewardBehavior.Params> {
	public record Params(LevelValue heal, EffectPayload payload, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("heal").validate(v -> CombatBehaviors.atLeast(v, 0.0, "heal")).forGetter(Params::heal),
				EffectPayload.FIELDS.forGetter(Params::payload),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("kill_reward", Params.CODEC, new KillRewardBehavior());

	@Override
	public void onKill(ActiveBehavior<Params> self, ServerPlayer player, LivingEntity victim) {
		if (victim == player || !player.isAlive() || victim.getMaxHealth() < AbsorbCaps.KILL_REWARD_MIN_VICTIM_MAX_HEALTH) return;
		Params p = self.params();
		if (!p.condition().test(player) || !CombatBehaviors.tryCooldown(player, self, AbsorbCaps.KILL_REWARD_COOLDOWN_TICKS)) return;
		float heal = (float) p.heal().at(self.level());
		if (heal > 0.0F) player.heal(heal);
		p.payload().apply(player, player, self.level());
	}
}
