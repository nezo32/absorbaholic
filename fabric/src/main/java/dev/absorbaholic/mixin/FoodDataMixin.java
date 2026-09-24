package dev.absorbaholic.mixin;

import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code tick(Lnet/minecraft/server/level/ServerPlayer;)V} HEAD / RETURN mark "natural regeneration" for
 * {@code TraitEngine.modifyHeal(..., natural)} (heal_multiplier {@code source: natural}): every heal inside
 * FoodData#tick is food regeneration.
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
	@Inject(method = "tick(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
	private void absorbaholic$beginRegen(ServerPlayer player, CallbackInfo ci) {
		TraitEngine.beginNaturalRegen();
	}

	@Inject(method = "tick(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("RETURN"))
	private void absorbaholic$endRegen(ServerPlayer player, CallbackInfo ci) {
		TraitEngine.endNaturalRegen();
	}
}
