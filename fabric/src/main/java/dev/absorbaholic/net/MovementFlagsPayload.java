package dev.absorbaholic.net;

import dev.absorbaholic.Absorbaholic;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** S2C to the owner: current {@code MovementFlags} (client-side physics: water walking, climbing, gliding). */
public record MovementFlagsPayload(int flags) implements CustomPacketPayload {
	public static final Type<MovementFlagsPayload> TYPE = new Type<>(Absorbaholic.id("movement"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MovementFlagsPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_INT, MovementFlagsPayload::flags, MovementFlagsPayload::new);

	@Override
	public Type<MovementFlagsPayload> type() {
		return TYPE;
	}
}
