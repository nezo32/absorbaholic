package dev.absorbaholic.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@code onConsume(Level, LivingEntity, ItemStack, Consumable)V}: {@code @ModifyArg} on the
 * {@code FoodData.eat(FoodProperties)V} invoke (user and stack from the arguments) → {@code TraitEngine.modifyFood}
 * for a ServerPlayer (food_modifier). The client keeps the vanilla value; the server's food level is synced.
 */
@Mixin(FoodProperties.class)
public abstract class FoodPropertiesMixin {
	@ModifyArg(method = "onConsume",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;eat(Lnet/minecraft/world/food/FoodProperties;)V"))
	private FoodProperties absorbaholic$modifyFood(FoodProperties food, @Local(argsOnly = true) LivingEntity user,
			@Local(argsOnly = true) ItemStack stack) {
		return user instanceof ServerPlayer player ? TraitEngine.modifyFood(player, stack, food) : food;
	}
}
