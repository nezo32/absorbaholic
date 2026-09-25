package dev.absorbaholic.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Registers every payload type (both directions). Called first from Absorbaholic#onInitialize (runs on both sides). */
public final class AbsorbNetworking {
	/** Ticks without an {@link AbsorbStartPayload} heartbeat after which the server cancels a channel. */
	public static final int HEARTBEAT_TIMEOUT = 5;

	private AbsorbNetworking() {}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(SourcesSyncPayload.TYPE, SourcesSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WorldStatePayload.TYPE, WorldStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DiscoveredPayload.TYPE, DiscoveredPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TraitsPayload.TYPE, TraitsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AbsorbedPayload.TYPE, AbsorbedPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ChannelStatePayload.TYPE, ChannelStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MovementPayload.TYPE, MovementPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AuraPayload.TYPE, AuraPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AbsorbStartPayload.TYPE, AbsorbStartPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AbsorbCancelPayload.TYPE, AbsorbCancelPayload.CODEC);
	}
}
