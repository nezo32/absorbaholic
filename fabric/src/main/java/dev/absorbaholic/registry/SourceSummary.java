package dev.absorbaholic.registry;

import java.util.Optional;

import dev.absorbaholic.core.Tier;
import dev.absorbaholic.net.NetCodecs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * What the client knows about a source (synced in {@code SourcesSyncPayload}): enough for the HUD hint, the traits
 * screen, the aura and the use-key intercept. No attributes / behaviors: those are server-only.
 *
 * @param nameKey translation key of the source's display name ({@link SourceDefinition#nameKey()})
 */
public record SourceSummary(Identifier id, SourceTargets targets, String nameKey, Optional<Identifier> icon, int color, Tier tier,
		int maxLevel, String traitKey, String weaknessKey) implements SourceLike {
	public static final StreamCodec<ByteBuf, SourceSummary> STREAM_CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, SourceSummary::id,
			SourceTargets.STREAM_CODEC, SourceSummary::targets,
			ByteBufCodecs.STRING_UTF8, SourceSummary::nameKey,
			ByteBufCodecs.optional(Identifier.STREAM_CODEC), SourceSummary::icon,
			ByteBufCodecs.INT, SourceSummary::color,
			NetCodecs.enumCodec(Tier.class), SourceSummary::tier,
			ByteBufCodecs.VAR_INT, SourceSummary::maxLevel,
			ByteBufCodecs.STRING_UTF8, SourceSummary::traitKey,
			ByteBufCodecs.STRING_UTF8, SourceSummary::weaknessKey,
			SourceSummary::new);

	/** With the {@linkplain SourceDefinition#defaultNameKey default display name}. */
	public SourceSummary(Identifier id, SourceTargets targets, Optional<Identifier> icon, int color, Tier tier, int maxLevel,
			String traitKey, String weaknessKey) {
		this(id, targets, SourceDefinition.defaultNameKey(id, targets), icon, color, tier, maxLevel, traitKey, weaknessKey);
	}

	public static SourceSummary of(SourceDefinition d) {
		return new SourceSummary(d.id(), d.targets(), d.nameKey(), d.icon(), d.color(), d.tier(), d.maxLevel(), d.trait().key(),
				d.weakness().key());
	}

	public String traitLangKey() {
		return "absorbaholic.trait." + traitKey;
	}

	public String weaknessLangKey() {
		return "absorbaholic.weakness." + weaknessKey;
	}
}
