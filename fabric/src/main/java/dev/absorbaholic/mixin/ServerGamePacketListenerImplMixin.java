package dev.absorbaholic.mixin;

import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swing detection for the sneak_swing trigger, portable across the 26.2 swing packet ({@code handleAnimate}) and the
 * 26.3 punch packet ({@code handlePunch}): one injector naming both methods; exactly one exists per version, and the
 * handler takes only the {@code CallbackInfo}, so it never names the version-specific packet class. The engine treats
 * a swing as sneak_swing only if sneaking with no block within reach (server raycast, so never while mining).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = {"handleAnimate", "handlePunch"}, at = @At("TAIL"), require = 1)
	private void absorbaholic$swing(CallbackInfo ci) {
		TraitEngine.onSwing(player);
	}
}
