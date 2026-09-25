package dev.absorbaholic.net;

import java.util.UUID;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.player.PlayerTraits;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C: the traits of {@code owner}. Own traits after every change (openScreen=false); anyone's traits with
 * openScreen=true in answer to {@code /absorbaholic traits [player]}.
 */
public record TraitsPayload(UUID owner, String ownerName, PlayerTraits traits, boolean openScreen) implements CustomPacketPayload {
	public static final Type<TraitsPayload> TYPE = new Type<>(Absorbaholic.id("traits"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TraitsPayload> CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, TraitsPayload::owner,
			ByteBufCodecs.stringUtf8(64), TraitsPayload::ownerName,
			PlayerTraits.STREAM_CODEC, TraitsPayload::traits,
			ByteBufCodecs.BOOL, TraitsPayload::openScreen,
			TraitsPayload::new);

	@Override
	public Type<TraitsPayload> type() {
		return TYPE;
	}
}
