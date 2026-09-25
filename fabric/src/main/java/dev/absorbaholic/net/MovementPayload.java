package dev.absorbaholic.net;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.trait.MovementState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C to the owner: the current {@link MovementState} (client-side physics: fluid walking, wall climbing, gliding,
 * sinking). The client stores it on its LocalPlayer via {@code MovementFlagsHolder}.
 */
public record MovementPayload(MovementState state) implements CustomPacketPayload {
	public static final Type<MovementPayload> TYPE = new Type<>(Absorbaholic.id("movement"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MovementPayload> CODEC =
			StreamCodec.composite(MovementState.STREAM_CODEC, MovementPayload::state, MovementPayload::new);

	@Override
	public Type<MovementPayload> type() {
		return TYPE;
	}
}
