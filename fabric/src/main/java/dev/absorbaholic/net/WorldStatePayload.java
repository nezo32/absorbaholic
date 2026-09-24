package dev.absorbaholic.net;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.world.AbsorbWorldSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** S2C, on join and whenever a world setting changes. */
public record WorldStatePayload(boolean enabled, boolean hints, boolean keepOnDeath, int maxTraits) implements CustomPacketPayload {
	public static final Type<WorldStatePayload> TYPE = new Type<>(Absorbaholic.id("world_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, WorldStatePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, WorldStatePayload::enabled,
			ByteBufCodecs.BOOL, WorldStatePayload::hints,
			ByteBufCodecs.BOOL, WorldStatePayload::keepOnDeath,
			ByteBufCodecs.VAR_INT, WorldStatePayload::maxTraits,
			WorldStatePayload::new);

	public static WorldStatePayload of(AbsorbWorldSettings s) {
		return new WorldStatePayload(s.enabled(), s.hints(), s.keepOnDeath(), s.maxTraits());
	}

	@Override
	public Type<WorldStatePayload> type() {
		return TYPE;
	}
}
