package dev.absorbaholic.trait;

/**
 * Duck interface on {@code Player} (PlayerMixin): the current {@link MovementFlags}. Set by the server engine on the
 * ServerPlayer and by the client from {@code MovementFlagsPayload} on the LocalPlayer; read by the movement mixins.
 */
public interface MovementFlagsHolder {
	int absorbaholic$movementFlags();

	void absorbaholic$setMovementFlags(int flags);
}
