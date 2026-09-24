package dev.absorbaholic.net;

import java.util.List;

import dev.absorbaholic.Absorbaholic;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S2C: discovered source ids. {@code replace} = the full set (join), else additions (someone discovered one). */
public record DiscoveredPayload(boolean replace, List<Identifier> sources) implements CustomPacketPayload {
	public static final Type<DiscoveredPayload> TYPE = new Type<>(Absorbaholic.id("discovered"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DiscoveredPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, DiscoveredPayload::replace,
			NetCodecs.IDENTIFIER_LIST, DiscoveredPayload::sources,
			DiscoveredPayload::new);

	public DiscoveredPayload {
		sources = List.copyOf(sources);
	}

	@Override
	public Type<DiscoveredPayload> type() {
		return TYPE;
	}
}
