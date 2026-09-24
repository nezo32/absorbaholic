package dev.absorbaholic.absorb;

/**
 * WP-ABSORB. Server side of absorbing:
 * <ul>
 * <li>receives {@code AbsorbStartPayload} / {@code AbsorbCancelPayload} (ServerPlayNetworking global receivers);</li>
 * <li>validation includes {@code AbsorbRules.isProtected} (refuse "protected") and non-empty containers (refuse);</li>
 * <li>END_SERVER_TICK: for each player with a channel validate (see ARCHITECTURE "Absorb interaction"), send
 *     {@code ChannelStatePayload}, particles + rising sound every 5 ticks; at {@code AbsorbCaps.CHANNEL_TICKS} call
 *     {@link AbsorbService#complete};</li>
 * <li>UseBlockCallback / UseEntityCallback (server side): return FAIL while a modded player's absorb gesture targets
 *     an absorbable (no door / chest / villager interaction).</li>
 * </ul>
 */
public final class AbsorbHandler {
	private AbsorbHandler() {}

	public static void register() {
		// TODO(WP-ABSORB)
	}
}
