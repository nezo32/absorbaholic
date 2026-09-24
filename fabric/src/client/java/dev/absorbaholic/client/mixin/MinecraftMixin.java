package dev.absorbaholic.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-CLIENT. {@code startUseItem()V} HEAD cancellable: cancel while {@code AbsorbInput.shouldIntercept(mc)} (runs for
 * the first click and every held-repeat tick). {@code startAttack()Z} HEAD: sneak-swing at air → ActionPayload.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
}
