package dev.absorbaholic.net;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.absorb.AbsorbTarget;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: the use key is held on {@code target} (sneaking, empty hand). Sent on press and then every tick while held
 * (a heartbeat: the server cancels a channel that got no heartbeat for {@code AbsorbNetworking#HEARTBEAT_TIMEOUT} ticks).
 * A different target restarts the channel.
 */
public record AbsorbStartPayload(AbsorbTarget target) implements CustomPacketPayload {
	public static final Type<AbsorbStartPayload> TYPE = new Type<>(Absorbaholic.id("absorb_start"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AbsorbStartPayload> CODEC =
			StreamCodec.composite(AbsorbTarget.STREAM_CODEC, AbsorbStartPayload::target, AbsorbStartPayload::new);

	@Override
	public Type<AbsorbStartPayload> type() {
		return TYPE;
	}
}
