package dev.absorbaholic.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * One absorbed source of a player. {@code weaknessLevel} 0 = the weakness is inactive (pure). {@code mutated} /
 * {@code pure} are sticky display tags: at least one absorption of this source mutated / was pure. The source id may
 * refer to a source that no longer exists (datapack removed): such entries are kept but inactive and shown greyed.
 */
public record TraitEntry(Identifier source, int traitLevel, int weaknessLevel, boolean mutated, boolean pure) {
	public static final Codec<TraitEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("source").forGetter(TraitEntry::source),
			Codec.intRange(0, 255).fieldOf("trait").forGetter(TraitEntry::traitLevel),
			Codec.intRange(0, 255).optionalFieldOf("weakness", 0).forGetter(TraitEntry::weaknessLevel),
			Codec.BOOL.optionalFieldOf("mutated", false).forGetter(TraitEntry::mutated),
			Codec.BOOL.optionalFieldOf("pure", false).forGetter(TraitEntry::pure)
	).apply(i, TraitEntry::new));

	public static final StreamCodec<ByteBuf, TraitEntry> STREAM_CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, TraitEntry::source,
			ByteBufCodecs.VAR_INT, TraitEntry::traitLevel,
			ByteBufCodecs.VAR_INT, TraitEntry::weaknessLevel,
			ByteBufCodecs.BOOL, TraitEntry::mutated,
			ByteBufCodecs.BOOL, TraitEntry::pure,
			TraitEntry::new);
}
