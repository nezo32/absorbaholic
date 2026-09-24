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

/**
 * {@code setTarget(LivingEntity)} HEAD cancellable: a mob may not target a ServerPlayer when
 * {@code TraitEngine.preventsTargeting(mob, player)} (unless the player hurt it: {@code mob.getLastHurtByMob() == player}).
 * Also covers the enderman's stare-aggro, whose look goal ends in setTarget (the renamed class is never named).
 */
@Mixin(Mob.class)
public abstract class MobMixin {
	@Inject(method = "setTarget(Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"), cancellable = true)
	private void absorbaholic$preventTargeting(@Nullable LivingEntity target, CallbackInfo ci) {
		if (target instanceof ServerPlayer player && TraitEngine.preventsTargeting((Mob) (Object) this, player)) ci.cancel();
	}
}
