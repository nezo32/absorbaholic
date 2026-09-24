package dev.absorbaholic.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. {@code onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V} HEAD: target is a ServerPlayer → {@code TraitEngine.onHitByProjectile} (struck_by; fires for zero-damage snowballs too).
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
}
