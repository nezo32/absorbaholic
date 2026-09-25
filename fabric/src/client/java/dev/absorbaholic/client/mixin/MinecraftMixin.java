package dev.absorbaholic.client.mixin;

import dev.absorbaholic.client.input.AbsorbInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swallows vanilla use ({@code startUseItem()V}, HEAD) while {@link AbsorbInput#shouldIntercept} says the use key
 * drives the absorb gesture. It runs for the first click and for every held-repeat tick; since the cancel skips
 * {@code rightClickDelay = 4}, vanilla use resumes on the very next tick once the gesture ends.
 * (Sneak-swing is detected server side from vanilla packets.)
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
	private void absorbaholic$interceptUse(CallbackInfo ci) {
		if (AbsorbInput.shouldIntercept((Minecraft) (Object) this)) ci.cancel();
	}
}
