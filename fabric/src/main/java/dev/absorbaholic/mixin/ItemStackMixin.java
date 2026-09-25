package dev.absorbaholic.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code processDurabilityChange(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;)I}
 * (private, same in 26.2 / 26.3; after Unbreaking) {@code @ModifyReturnValue}: player non-null and positive result →
 * {@code TraitEngine.modifyDurabilityDamage} (durability_multiplier, stochastic rounding).
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@ModifyReturnValue(method = "processDurabilityChange(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;)I",
			at = @At("RETURN"))
	private int absorbaholic$durability(int result, @Local(argsOnly = true) @Nullable ServerPlayer player) {
		return player != null && result > 0 ? TraitEngine.modifyDurabilityDamage(player, (ItemStack) (Object) this, result) : result;
	}
}
