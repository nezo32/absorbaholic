package dev.absorbaholic.net;

import java.util.Optional;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.MutationRoll;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C to the absorbing player (modded clients only; vanilla clients get plain title / actionbar / sound packets).
 * The client composes the title itself so it can measure it: {@code absorbaholic.absorbed.title} ("🧬 Absorbed: %s"
 * with {@code sourceName}) if it fits the screen at title scale, else {@code absorbaholic.absorbed.title.short}
 * ("🧬 Absorbed!") with the source name moved to the subtitle; a mutation / pure outcome adds its own subtitle line.
 * It shows them and plays the outcome's sound according to its NotifySettings.
 *
 * @param actionbar "&lt;trait&gt; &lt;level&gt; · &lt;weakness&gt; &lt;level&gt;", built by the server
 * @param notice extra chat line for this player, e.g. the oldest trait was evicted to make room
 */
public record AbsorbedPayload(Component sourceName, Component actionbar, MutationRoll.Outcome outcome, Optional<Component> notice)
		implements CustomPacketPayload {
	public static final Type<AbsorbedPayload> TYPE = new Type<>(Absorbaholic.id("absorbed"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AbsorbedPayload> CODEC = StreamCodec.composite(
			ComponentSerialization.TRUSTED_STREAM_CODEC, AbsorbedPayload::sourceName,
			ComponentSerialization.TRUSTED_STREAM_CODEC, AbsorbedPayload::actionbar,
			NetCodecs.enumCodec(MutationRoll.Outcome.class), AbsorbedPayload::outcome,
			ComponentSerialization.TRUSTED_OPTIONAL_STREAM_CODEC, AbsorbedPayload::notice,
			AbsorbedPayload::new);

	@Override
	public Type<AbsorbedPayload> type() {
		return TYPE;
	}
}
