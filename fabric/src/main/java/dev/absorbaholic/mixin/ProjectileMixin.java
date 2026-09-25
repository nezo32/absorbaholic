package dev.absorbaholic.mixin;

import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Projectile hit on a player → {@code TraitEngine.onHitByProjectile} (struck_by; fires for zero-damage snowballs too).
 * Injected in {@code onHit(HitResult)} right before its {@code onHitEntity} call rather than at the HEAD of
 * {@code onHitEntity}, because some overrides (the trident's) never call {@code super.onHitEntity}; every
 * {@code onHit} override calls {@code super.onHit}. Before damage is dealt.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
	@Inject(method = "onHit(Lnet/minecraft/world/phys/HitResult;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V"))
	private void absorbaholic$hitEntity(HitResult hit, CallbackInfo ci) {
		if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof ServerPlayer player) {
			TraitEngine.onHitByProjectile(player, (Projectile) (Object) this);
		}
	}
}
