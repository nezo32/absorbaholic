package dev.absorbaholic.net;

import dev.absorbaholic.Absorbaholic;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: the use key was released (or the client lost the target); cancel any running channel. */
public record AbsorbCancelPayload() implements CustomPacketPayload {
	public static final AbsorbCancelPayload INSTANCE = new AbsorbCancelPayload();
	public static final Type<AbsorbCancelPayload> TYPE = new Type<>(Absorbaholic.id("absorb_cancel"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AbsorbCancelPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<AbsorbCancelPayload> type() {
		return TYPE;
	}
}
