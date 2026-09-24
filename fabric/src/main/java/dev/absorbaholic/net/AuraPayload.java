package dev.absorbaholic.net;

import dev.absorbaholic.Absorbaholic;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C to the player and everyone tracking them (on change and on start-tracking): the aura of entity
 * {@code entityId}. {@code strength} 0 = no aura (no traits, mode OFF, creative / spectator).
 */
public record AuraPayload(int entityId, int color, float strength) implements CustomPacketPayload {
	public static final Type<AuraPayload> TYPE = new Type<>(Absorbaholic.id("aura"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AuraPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, AuraPayload::entityId,
			ByteBufCodecs.INT, AuraPayload::color,
			ByteBufCodecs.FLOAT, AuraPayload::strength,
			AuraPayload::new);

	@Override
	public Type<AuraPayload> type() {
		return TYPE;
	}
}
