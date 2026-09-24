package dev.absorbaholic.net;

import dev.absorbaholic.Absorbaholic;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: an input gesture the server cannot see by itself. SNEAK_SWING = attack key at air while sneaking. */
public record ActionPayload(Action action) implements CustomPacketPayload {
	public enum Action { SNEAK_SWING }

	public static final Type<ActionPayload> TYPE = new Type<>(Absorbaholic.id("action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC =
			StreamCodec.composite(NetCodecs.enumCodec(Action.class), ActionPayload::action, ActionPayload::new);

	@Override
	public Type<ActionPayload> type() {
		return TYPE;
	}
}
