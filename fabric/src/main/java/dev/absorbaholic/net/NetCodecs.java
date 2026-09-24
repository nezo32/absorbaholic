package dev.absorbaholic.net;

import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/** Small shared stream codecs for our payloads. */
public final class NetCodecs {
	/** Max list sizes accepted from the network (defensive; sources and traits are far below). */
	public static final int MAX_LIST = 4096;

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
