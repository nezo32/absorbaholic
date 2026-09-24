package dev.absorbaholic.mixin;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. {@code processDurabilityChange(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;)I}
 * (private, same in 26.2 / 26.3) {@code @ModifyReturnValue}: player non-null and positive result →
 * {@code TraitEngine.modifyDurabilityDamage}.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
}
