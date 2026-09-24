package dev.absorbaholic.absorb;

/**
 * WP-ABSORB. Everything the players see and hear:
 * <ul>
 * <li>{@code absorbed(player, source, result)}: modded client → {@code AbsorbedPayload} (the client composes title /
 *     subtitle / actionbar and plays sounds by its NotifySettings); vanilla client → short title
 *     {@code absorbaholic.absorbed.title.short} + subtitle (source name, plus the special line), actionbar and sound
 *     packets, all built with {@code Texts.tr} (English fallback). Particles (server-spawned, everyone) always.
 *     Mutation / pure: additionally a system chat broadcast to all players ({@code absorbaholic.announce.*}).</li>
 * <li>{@code refused(player, reasonKey, args...)}: actionbar message (modded only; vanilla clients cannot channel).</li>
 * <li>{@code channelTick(player, channel, now)}: particles converging on the player + rising sound every 5 ticks.</li>
 * </ul>
 */
public final class AbsorbFeedback {
	private AbsorbFeedback() {}
}
