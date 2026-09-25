package dev.absorbaholic.net;

import dev.absorbaholic.Absorbaholic;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C to the channeling player: the server's view of the channel, for the HUD progress ring and to stop the client
 * re-sending start. STARTED carries {@code totalTicks}; {@code elapsedTicks} lets the client resync.
 * CANCELLED / COMPLETED end it (a refusal reason arrives separately as an actionbar message).
 */
public record ChannelStatePayload(Status status, int elapsedTicks, int totalTicks) implements CustomPacketPayload {
	public enum Status { STARTED, PROGRESS, CANCELLED, COMPLETED }

	public static final Type<ChannelStatePayload> TYPE = new Type<>(Absorbaholic.id("channel"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ChannelStatePayload> CODEC = StreamCodec.composite(
			NetCodecs.enumCodec(Status.class), ChannelStatePayload::status,
			ByteBufCodecs.VAR_INT, ChannelStatePayload::elapsedTicks,
			ByteBufCodecs.VAR_INT, ChannelStatePayload::totalTicks,
			ChannelStatePayload::new);

	@Override
	public Type<ChannelStatePayload> type() {
		return TYPE;
	}
}
