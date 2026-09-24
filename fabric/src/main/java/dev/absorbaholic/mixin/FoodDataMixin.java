package dev.absorbaholic.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. {@code tick(Lnet/minecraft/server/level/ServerPlayer;)V} HEAD / RETURN set / clear a thread-local "natural regeneration" flag read by {@code TraitEngine.modifyHeal(..., natural)}.
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
}
