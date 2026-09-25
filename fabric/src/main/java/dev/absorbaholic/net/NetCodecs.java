package dev.absorbaholic.net;

import java.util.List;

import dev.absorbaholic.core.AbsorbCaps;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/** Small shared stream codecs for our payloads. */
public final class NetCodecs {
	/** Max list sizes accepted from the network ({@code AbsorbCaps.NET_MAX_LIST_SIZE}). */
	public static final int MAX_LIST = AbsorbCaps.NET_MAX_LIST_SIZE;

	public static final StreamCodec<ByteBuf, List<Identifier>> IDENTIFIER_LIST =
			Identifier.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LIST));

	private NetCodecs() {}

	/** Enum by ordinal (var-int); an out-of-range ordinal fails decoding. */
	public static <E extends Enum<E>> StreamCodec<ByteBuf, E> enumCodec(Class<E> type) {
		E[] values = type.getEnumConstants();
		return ByteBufCodecs.VAR_INT.map(i -> {
			if (i < 0 || i >= values.length) throw new IllegalArgumentException("bad " + type.getSimpleName() + " ordinal " + i);
			return values[i];
		}, Enum::ordinal);
	}
}
