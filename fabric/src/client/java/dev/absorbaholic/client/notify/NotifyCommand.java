package dev.absorbaholic.client.notify;

/**
 * WP-CLIENT. Client command {@code /absorbaholic-notify status | sound [on|off] | message [on|off]} (a setting without
 * a value is flipped), same as the reference's NotifyCommand; keys {@code absorbaholic.command.notify.*}.
 */
public final class NotifyCommand {
	public static final String ROOT = "absorbaholic-notify";

	private NotifyCommand() {}

	public static void register() {
		// TODO(WP-CLIENT)
	}
}
