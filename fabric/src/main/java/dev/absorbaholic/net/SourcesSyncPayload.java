package dev.absorbaholic.net;

import java.util.List;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.registry.SourceSummary;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** S2C, on join and after /reload: every valid source (replaces the client's list). */
public record SourcesSyncPayload(List<SourceSummary> sources) implements CustomPacketPayload {
	public static final Type<SourcesSyncPayload> TYPE = new Type<>(Absorbaholic.id("sources"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SourcesSyncPayload> CODEC = SourceSummary.STREAM_CODEC
			.apply(ByteBufCodecs.list(NetCodecs.MAX_LIST))
			.map(SourcesSyncPayload::new, SourcesSyncPayload::sources)
			.cast();

	public SourcesSyncPayload {
		sources = List.copyOf(sources);
	}

	@Override
	public Type<SourcesSyncPayload> type() {
		return TYPE;
	}
}
