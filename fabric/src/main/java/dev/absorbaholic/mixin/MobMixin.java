package dev.absorbaholic.mixin;

import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mob may not target a ServerPlayer when {@code TraitEngine.preventsTargeting(mob, player)} (unless the player hurt
 * it: {@code mob.getLastHurtByMob() == player}):
 * <ul>
 * <li>{@code setTarget(LivingEntity)} HEAD cancellable: goal-based targeting, including the enderman's stare-aggro,
 *     whose look goal ends in setTarget (the renamed class is never named);</li>
 * <li>{@code canAttack(LivingEntity)} HEAD cancellable → false: brain-driven mobs (piglins, hoglins …) pick targets
 *     with {@code StartAttacking}, which never calls setTarget but checks canAttack; vanilla also re-checks it on every
 *     {@code getTarget()}, so a target that became forbidden is dropped.</li>
 * </ul>
 */
@Mixin(Mob.class)
public abstract class MobMixin {
	@Inject(method = "setTarget(Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"), cancellable = true)
	private void absorbaholic$preventTargeting(@Nullable LivingEntity target, CallbackInfo ci) {
		if (target instanceof ServerPlayer player && TraitEngine.preventsTargeting((Mob) (Object) this, player)) ci.cancel();
	}

	@Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
	private void absorbaholic$preventAttack(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
		if (target instanceof ServerPlayer player && TraitEngine.preventsTargeting((Mob) (Object) this, player)) cir.setReturnValue(false);
	}
}
