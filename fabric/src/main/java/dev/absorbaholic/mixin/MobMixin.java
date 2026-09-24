package dev.absorbaholic.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. {@code setTarget(Lnet/minecraft/world/entity/LivingEntity;)V} HEAD cancellable: a mob may not target a
 * ServerPlayer when {@code TraitEngine.preventsTargeting(mob, player)} (unless the player hurt it:
 * {@code mob.getLastHurtByMob() == player}).
 */
@Mixin(Mob.class)
public abstract class MobMixin {
}
