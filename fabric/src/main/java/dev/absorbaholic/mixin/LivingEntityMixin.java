package dev.absorbaholic.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. LivingEntity-level trait hooks (all check {@code this instanceof ServerPlayer} / {@code Player} first):
 * <ul>
 * <li>{@code hurtServer} HEAD {@code @ModifyVariable(argsOnly, float)}: victim ServerPlayer →
 *     {@code TraitEngine.modifyIncomingDamage} (trait floor + weakness extra through the gate); attacker
 *     ({@code source.getEntity()}) ServerPlayer → {@code TraitEngine.modifyOutgoingDamage}.</li>
 * <li>{@code heal(F)V} HEAD {@code @ModifyVariable(argsOnly)} → {@code TraitEngine.modifyHeal(p, amount, natural)}.</li>
 * <li>{@code addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z} HEAD
 *     {@code @ModifyVariable(argsOnly)} → {@code TraitEngine.modifyEffect} (deny is ServerMobEffectEvents.ALLOW_ADD).</li>
 * <li>{@code canStandOnFluid(Lnet/minecraft/world/level/material/FluidState;)Z} HEAD cancellable →
 *     WALK_ON_WATER / WALK_ON_LAVA flags (not while sneaking). Both sides (client physics). SINK_IN_WATER: WP-ENGINE
 *     adds the travel-in-fluid tweak (vy = max(vy - sinkSpeed, SINK_MAX_FALL_VELOCITY), no swim-up) in this mixin.</li>
 * <li>{@code onClimbable()Z} HEAD cancellable → true for a Player with CLIMB_WALLS and {@code horizontalCollision}.
 *     Both sides.</li>
 * <li>{@code getVisibilityPercent} by NAME ONLY (26.2: (Entity), 26.3: (ServerLevel, Entity)), {@code @At("RETURN")},
 *     handler takes only {@code CallbackInfoReturnable<Double>} (or MixinExtras {@code @Local(argsOnly = true) Entity})
 *     → {@code TraitEngine.visibilityFactor}. Note: &gt; 1 cannot extend detection past the mob's follow range.</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
}
