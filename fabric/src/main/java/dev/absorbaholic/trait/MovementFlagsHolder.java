package dev.absorbaholic.trait;

/**
 * Duck interface on {@code Player} (PlayerMixin): the current {@link MovementState}. Set by the server engine on the
 * ServerPlayer and by the client from {@code MovementPayload} on the LocalPlayer; read by the movement mixins and the
 * elytra event on both sides. Never null.
 */
public interface MovementFlagsHolder {
	MovementState absorbaholic$movement();

	void absorbaholic$setMovement(MovementState state);
}
