package dev.absorbaholic.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. LivingEntity-level trait hooks (all check {@code this instanceof ServerPlayer} / {@code Player} first):
 * <ul>
 * <li>{@code hurtServer} HEAD {@code @ModifyVariable(argsOnly, float)}: victim ServerPlayer →
 *     {@code TraitEngine.modifyIncomingDamage} (trait floor + weakness extra through the gate); attacker
 *     ({@code source.getEntity()}) ServerPlayer → {@code TraitEngine.modifyOutgoingDamage}.</li>
 * <li>{@code heal(F)V} HEAD {@code @ModifyVariable(argsOnly)} → {@code TraitEngine.modifyHeal}.</li>
 * <li>{@code addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z} HEAD
 *     {@code @ModifyVariable(argsOnly)} → {@code TraitEngine.modifyEffect} (deny is ServerMobEffectEvents.ALLOW_ADD).</li>
 * <li>{@code canStandOnFluid(Lnet/minecraft/world/level/material/FluidState;)Z} {@code @ModifyReturnValue} →
 *     WALK_ON_WATER / WALK_ON_LAVA flags (not while sneaking). Both sides.</li>
 * <li>{@code onClimbable()Z} {@code @ModifyReturnValue} → CLIMB_WALLS flag and {@code horizontalCollision}. Both sides.</li>
 * <li>{@code getVisibilityPercent} (name only: 26.2 has (Entity), 26.3 has (ServerLevel, Entity))
 *     {@code @ModifyReturnValue} with {@code @Local(argsOnly = true) Entity} → {@code TraitEngine.visibilityFactor}.</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
}
