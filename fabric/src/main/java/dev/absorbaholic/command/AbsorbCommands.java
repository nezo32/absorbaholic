package dev.absorbaholic.command;

/**
 * WP-CMD. {@code /absorbaholic}: {@code on|off|status}, {@code keep-on-death on|off|status}, {@code hints on|off|status},
 * {@code max [n]} (1..64), {@code remove <player> <source>} (suggestions = that player's sources), {@code reset <player>}
 * — all permission level 2 (Commands.LEVEL_GAMEMASTERS); {@code traits} (self, anyone) and {@code traits <player>} (op).
 * Bare {@code /absorbaholic} = status (anyone). Feedback via {@code Texts.tr} (vanilla clients may run commands).
 * {@code traits}: modded client → {@code TraitsSync.openScreen}; vanilla client → chat listing.
 */
public final class AbsorbCommands {
	private AbsorbCommands() {}

	/** Called from Absorbaholic#onInitialize. */
	public static void register() {
		// TODO(WP-CMD): CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> ...)
	}
}
