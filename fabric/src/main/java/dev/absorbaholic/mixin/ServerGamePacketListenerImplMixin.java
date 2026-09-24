package dev.absorbaholic.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. Swing detection for the sneak_swing trigger, portable across the 26.2 swing packet and the 26.3 punch packet: {@code @Inject(method = {"handleAnimate", "handlePunch"}, at = @At("TAIL"), require = 1)} with a {@code CallbackInfo}-only handler (only one name exists per version; verify with -Pmc=26.2 and 26.3) → {@code TraitEngine.onSwing(player)}. The engine treats it as sneak_swing only if sneaking, no block within reach (server raycast) and not mining.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
}
