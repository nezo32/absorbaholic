package dev.absorbaholic.client;

/**
 * WP-CLIENT. Registers the client receivers of every S2C payload (they run on the render thread) and fills
 * {@link ClientState}: SourcesSync → setSources; WorldState → setWorld; Discovered → setDiscovered; Traits →
 * setOwnTraits if it is ours, and opens {@code TraitsScreen} when openScreen; Absorbed → NotifyClient.handle;
 * ChannelState → setChannel; Movement → {@code ((MovementFlagsHolder) mc.player).absorbaholic$setMovement(state)}
 * (also re-applied after respawn / dimension change, since the LocalPlayer is recreated: keep the last state in ClientState);
 * Aura → setAura. ClientPlayConnectionEvents.DISCONNECT → ClientState.reset().
 */
public final class ClientNetworking {
	private ClientNetworking() {}

	public static void register() {
		// TODO(WP-CLIENT)
	}
}
