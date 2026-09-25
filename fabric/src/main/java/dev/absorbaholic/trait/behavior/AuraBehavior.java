package dev.absorbaholic.trait.behavior;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
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
import dev.absorbaholic.trait.TargetFilter;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;

/**
 * {@code absorbaholic:aura}: every {@code interval} ticks (default 20) while the condition holds, matching living
 * entities ({@code targets}: {@code "hostile"}, {@code "all_mobs"} or an entity list) whose bounding box is within
 * {@code radius} (max 16) of the player's get the payload: {@code effect} at {@code amplifier} for {@code duration}
 * ticks (credited to the player) and / or ignition for {@code ignite_seconds} (fire-immune mobs are skipped). Never the
 * player, other players, owned / tamed animals, NoAI mobs, or bosses unless listed by id. At most 48 entities per pulse.
 * A radius &lt;= 0 disables the entry at that level (e.g. {@code [0, 8, 16]} unlocks at level II); an amplifier
 * &lt; 0, duration &lt;= 0 or ignite_seconds &lt;= 0 disables that part of the payload.
 *
 * <pre>{"type": "absorbaholic:aura", "targets": "hostile", "radius": [2, 3, 4], "interval": 40, "effect": "minecraft:weakness",
 *  "amplifier": [0, 0, 0], "duration": [60, 60, 60]}</pre>
 */
public final class AuraBehavior implements Behavior<AuraBehavior.Params> {
	public record Params(TargetFilter targets, LevelValue radius, int interval, Optional<Holder<MobEffect>> effect, Optional<LevelValue> amplifier,
			Optional<LevelValue> duration, Optional<LevelValue> igniteSeconds, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.<Params>mapCodec(i -> i.group(
				TargetFilter.CODEC.fieldOf("targets").forGetter(Params::targets),
				LevelValue.CODEC.fieldOf("radius").forGetter(Params::radius),
				Codec.intRange(AbsorbCaps.BEHAVIOR_MIN_TICK_INTERVAL, AbsorbCaps.BEHAVIOR_MAX_TICK_INTERVAL).optionalFieldOf("interval", 20).forGetter(Params::interval),
				MobEffect.CODEC.optionalFieldOf("effect").forGetter(Params::effect),
				LevelValue.CODEC.optionalFieldOf("amplifier").forGetter(Params::amplifier),
				LevelValue.CODEC.optionalFieldOf("duration").forGetter(Params::duration),
				LevelValue.CODEC.optionalFieldOf("ignite_seconds").forGetter(Params::igniteSeconds),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new)).validate(Params::validate);

		private DataResult<Params> validate() {
			if (targets.isAny()) return DataResult.error(() -> "\"targets\" must not be empty");
			if (effect.isPresent() != amplifier.isPresent() || effect.isPresent() != duration.isPresent()) {
				return DataResult.error(() -> "\"effect\", \"amplifier\" and \"duration\" go together");
			}
			if (effect.isEmpty() && igniteSeconds.isEmpty()) return DataResult.error(() -> "needs \"effect\" and / or \"ignite_seconds\"");
			return DataResult.success(this);
		}
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("aura", Params.CODEC, new AuraBehavior());

	@Override
	public int tickInterval(Params p) {
		return p.interval();
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		Params p = self.params();
		int level = self.level();
		double radius = Math.min(p.radius().at(level), AbsorbCaps.AURA_MAX_RADIUS);
		if (!(radius > 0.0)) return;
		int amplifier = p.amplifier().map(v -> v.atInt(level)).orElse(-1);
		int duration = p.duration().map(v -> v.atInt(level)).orElse(0);
		boolean applyEffect = p.effect().isPresent() && amplifier >= 0 && duration > 0;
		int fireTicks = (int) Math.round(p.igniteSeconds().map(v -> v.at(level)).orElse(0.0) * 20.0);
		if (!applyEffect && fireTicks <= 0) return;
		if (!EntryStates.condition(player, self, p.condition())) return;
		List<LivingEntity> found = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(radius),
				e -> e != player && eligible(e, player, p.targets()));
		int n = 0;
		for (LivingEntity e : found) {
			if (++n > AbsorbCaps.MOB_SCAN_MAX_MOBS) break;
			if (applyEffect) e.addEffect(new MobEffectInstance(p.effect().get(), duration, amplifier), player);
			if (fireTicks > 0 && !e.fireImmune() && e.getRemainingFireTicks() < fireTicks) e.setRemainingFireTicks(fireTicks);
		}
	}

	private static boolean eligible(LivingEntity e, ServerPlayer player, TargetFilter targets) {
		if (!e.isAlive() || e instanceof Player || !targets.test(e)) return false;
		if (e instanceof Mob mob) return MobRules.affectable(mob, player, targets);
		return !(e instanceof OwnableEntity owned && owned.getOwnerReference() != null);
	}
}
