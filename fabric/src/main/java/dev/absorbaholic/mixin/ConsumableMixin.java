package dev.absorbaholic.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code onConsume(Level, LivingEntity, ItemStack)ItemStack} (same in 26.2 / 26.3): HEAD captures
 * {@code stack.copy()} for a ServerPlayer user, RETURN → {@code TraitEngine.onItemConsumed(player, copy)} (after
 * vanilla applied food and effects and shrank the stack).
 */
@Mixin(Consumable.class)
public abstract class ConsumableMixin {
	@Inject(method = "onConsume(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
			at = @At("HEAD"))
	private void absorbaholic$captureStack(Level level, LivingEntity user, ItemStack stack, CallbackInfoReturnable<ItemStack> cir,
			@Share("absorbaholic$consumed") LocalRef<ItemStack> consumed) {
		if (user instanceof ServerPlayer) consumed.set(stack.copy());
	}

	@Inject(method = "onConsume(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
			at = @At("RETURN"))
	private void absorbaholic$consumed(Level level, LivingEntity user, ItemStack stack, CallbackInfoReturnable<ItemStack> cir,
			@Share("absorbaholic$consumed") LocalRef<ItemStack> consumed) {
		ItemStack copy = consumed.get();
		if (copy != null && user instanceof ServerPlayer player) TraitEngine.onItemConsumed(player, copy);
	}
}
