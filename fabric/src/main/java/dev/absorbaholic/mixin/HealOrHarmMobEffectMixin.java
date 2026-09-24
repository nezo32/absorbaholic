package dev.absorbaholic.mixin;

import dev.absorbaholic.trait.behavior.EffectModifierBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Instant health / instant damage ({@code HealOrHarmMobEffect}, package-private, identical in 26.2 and 26.3) on a
 * ServerPlayer with an {@code effect_modifier} {@code invert} entry: HEAD cancellable on both application paths
 * (instant potions, splash / lingering clouds, arrows, and a ticking instance from /effect) →
 * {@link EffectModifierBehavior#invertInstant}, which heals instead of harming or deals gated weakness damage instead
 * of healing.
 */
@Mixin(targets = "net.minecraft.world.effect.HealOrHarmMobEffect")
public abstract class HealOrHarmMobEffectMixin {
	@Shadow
	@Final
	private boolean isHarm;

	@Inject(method = "applyEffectTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;I)Z",
			at = @At("HEAD"), cancellable = true)
	private void absorbaholic$invertTick(ServerLevel level, LivingEntity mob, int amplification, CallbackInfoReturnable<Boolean> cir) {
		if (mob instanceof ServerPlayer player && EffectModifierBehavior.invertInstant(player, isHarm, amplification, 1.0, null)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "applyInstantaneousEffect(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/LivingEntity;ID)V",
			at = @At("HEAD"), cancellable = true)
	private void absorbaholic$invertInstant(ServerLevel level, @Nullable Entity source, @Nullable Entity owner, LivingEntity mob, int amplification,
			double scale, CallbackInfo ci) {
		if (mob instanceof ServerPlayer player && EffectModifierBehavior.invertInstant(player, isHarm, amplification, scale, owner)) ci.cancel();
	}
}
