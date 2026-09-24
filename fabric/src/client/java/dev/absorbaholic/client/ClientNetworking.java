package dev.absorbaholic.client;

/**
 * WP-CLIENT. Registers the client receivers of every S2C payload (they run on the render thread) and fills
 * {@link ClientState}: SourcesSync → setSources; WorldState → setWorld; Discovered → setDiscovered; Traits →
 * setOwnTraits if it is ours, and opens {@code TraitsScreen} when openScreen; Absorbed → NotifyClient.handle;
 * ChannelState → setChannel; MovementFlags → {@code ((MovementFlagsHolder) mc.player).absorbaholic$setMovementFlags};
 * Aura → setAura. ClientPlayConnectionEvents.DISCONNECT → ClientState.reset().
 */
public final class ClientNetworking {
	private ClientNetworking() {}

	public static void register() {
		// TODO(WP-CLIENT)
	}
}
