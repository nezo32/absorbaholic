package dev.absorbaholic.mixin;

import net.minecraft.world.food.FoodProperties;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. {@code onConsume(Level, LivingEntity, ItemStack, Consumable)V}: {@code @ModifyArg} on the {@code FoodData.eat(FoodProperties)V} invoke (with {@code @Local(argsOnly = true)} user and stack) → {@code TraitEngine.modifyFood}.
 */
@Mixin(FoodProperties.class)
public abstract class FoodPropertiesMixin {
}
