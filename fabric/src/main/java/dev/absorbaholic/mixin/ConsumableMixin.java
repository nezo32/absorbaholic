package dev.absorbaholic.mixin;

import net.minecraft.world.item.component.Consumable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. {@code onConsume(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;}
 * (same in 26.2 / 26.3): HEAD capture {@code stack.copy()} for a ServerPlayer user, RETURN →
 * {@code TraitEngine.onItemConsumed(player, copy)} (after vanilla applied food and effects).
 */
@Mixin(Consumable.class)
public abstract class ConsumableMixin {
}
