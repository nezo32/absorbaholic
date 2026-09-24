package dev.absorbaholic.registry;

import java.util.List;

import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.net.NetCodecs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/** What a source matches: direct block / entity type ids and tag ids (without '#'), of registry {@code kind}. */
public record SourceTargets(SourceKind kind, List<Identifier> ids, List<Identifier> tags) {
	public static final StreamCodec<ByteBuf, SourceTargets> STREAM_CODEC = StreamCodec.composite(
			NetCodecs.enumCodec(SourceKind.class), SourceTargets::kind,
			NetCodecs.IDENTIFIER_LIST, SourceTargets::ids,
			NetCodecs.IDENTIFIER_LIST, SourceTargets::tags,
			SourceTargets::new);

	public SourceTargets {
		ids = List.copyOf(ids);
		tags = List.copyOf(tags);
	}
}
