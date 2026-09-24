package dev.absorbaholic.client.notify;

import dev.absorbaholic.net.AbsorbedPayload;
import net.minecraft.client.Minecraft;

/**
 * WP-CLIENT. Shows {@code AbsorbedPayload} according to {@link NotifyConfig}: message on → title (measured: the full
 * {@code absorbaholic.absorbed.title} if {@code font.width(title) * 4 <= guiWidth - 8}, else
 * {@code absorbaholic.absorbed.title.short} with the source name in the subtitle), outcome subtitle line
 * ({@code absorbaholic.absorbed.subtitle.<outcome>}), actionbar, notice in chat; sound on → the outcome's sound.
 * Public static {@link #handle} so the client gametest can call it directly.
 */
public final class NotifyClient {
	private NotifyClient() {}

	public static void handle(AbsorbedPayload payload, Minecraft mc) {
		// TODO(WP-CLIENT)
	}
}
