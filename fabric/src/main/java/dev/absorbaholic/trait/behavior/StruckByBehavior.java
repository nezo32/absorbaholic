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
import dev.absorbaholic.trait.TargetFilter;
import dev.absorbaholic.trait.WeaknessDamage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * {@code absorbaholic:struck_by}: when a projectile hits the player (zero-damage snowballs included) and either the
 * projectile or its owner matches {@code entities} (entity ids / #tags), deal {@code damage} [L] as weakness damage
 * (through the gate, via {@link WeaknessDamage}), credited to the projectile's owner. At most once per
 * {@code AbsorbCaps.STRUCK_BY_COOLDOWN_TICKS}. Shared condition fields at hit time. It runs before the projectile's
 * own damage, so a damaging projectile adds only what exceeds this hit (vanilla hurt cooldown).
 *
 * <pre>{"type": "absorbaholic:struck_by", "entities": ["minecraft:snowball"], "damage": [2, 3, 4]}</pre>
 */
public final class StruckByBehavior implements Behavior<StruckByBehavior.Params> {
	public record Params(TargetFilter entities, LevelValue damage, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				TargetFilter.CODEC.fieldOf("entities").forGetter(Params::entities),
				LevelValue.CODEC.fieldOf("damage").forGetter(Params::damage),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("struck_by", Params.CODEC, new StruckByBehavior());

	@Override
	public void onHitByProjectile(ActiveBehavior<Params> self, ServerPlayer player, Projectile projectile) {
		Params p = self.params();
		Entity owner = projectile.getOwner();
		if (!p.entities().test(projectile) && (owner == null || !p.entities().test(owner))) return;
		float damage = (float) p.damage().at(self.level());
		if (!(damage > 0.0F) || !p.condition().test(player)) return;
		if (!CombatBehaviors.tryCooldown(player, self, AbsorbCaps.STRUCK_BY_COOLDOWN_TICKS)) return;
		WeaknessDamage.hurt(player, damage, owner);
	}
}
