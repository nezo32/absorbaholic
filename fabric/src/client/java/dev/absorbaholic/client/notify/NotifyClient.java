package dev.absorbaholic.client.notify;

import java.util.Locale;

import dev.absorbaholic.core.MutationRoll;
import dev.absorbaholic.core.NotifySettings;
import dev.absorbaholic.net.AbsorbedPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

/**
 * Shows an {@link AbsorbedPayload} according to {@link NotifyConfig}. Message ON: a title measured to fit (the full
 * "🧬 Absorbed: &lt;name&gt;" if it fits at title scale, else "🧬 Absorbed!" with the name moved to the subtitle), the
 * outcome's line for mutations and pure absorptions in the subtitle (or appended to the actionbar when the subtitle
 * would not fit at subtitle scale), the actionbar and the notice in chat. The title uses the title times currently in
 * effect (vanilla defaults unless the server set others), so it never changes how later titles are timed.
 * Sound ON: the outcome's sound. {@link #handle} is public so the client gametest can call it directly.
 */
public final class NotifyClient {
	/** The title is drawn at 4x scale, the subtitle at 2x; each must fit the GUI width minus the margin. */
	public static final int TITLE_SCALE = 4, SUBTITLE_SCALE = 2, TITLE_MARGIN = 8;
	private static final String KEY = "absorbaholic.absorbed.";

	private NotifyClient() {}

	/** Title, optional subtitle and the actionbar as composed for one screen width. */
	public record Lines(Component title, @Nullable Component subtitle, Component actionbar) {}

	/** What {@link #handle} showed or played (null = nothing of that kind); for the client gametest. */
	public record Shown(@Nullable Lines lines, @Nullable Component notice, @Nullable SoundEvent sound) {
		public static final Shown NOTHING = new Shown(null, null, null);
	}

	/** Sound, volume and pitch of an outcome (ARCHITECTURE §6.3, same as the vanilla-client fallback). */
	public record OutcomeSound(SoundEvent sound, float volume, float pitch) {}

	public static Shown handle(AbsorbedPayload payload, Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) return Shown.NOTHING;
		NotifySettings settings = NotifyConfig.get();
		Lines lines = null;
		Component notice = null;
		if (settings.message()) {
			lines = compose(payload.sourceName(), payload.actionbar(), payload.outcome(), mc.font, mc.getWindow().getGuiScaledWidth());
			Hud hud = mc.gui.hud;
			hud.clearTitles();
			if (lines.subtitle() != null) hud.setSubtitle(lines.subtitle());
			hud.setTitle(lines.title());
			player.sendOverlayMessage(lines.actionbar());
			notice = payload.notice().orElse(null);
			if (notice != null) hud.getChat().addClientSystemMessage(notice);
		}
		SoundEvent played = null;
		if (settings.sound()) {
			OutcomeSound s = soundOf(payload.outcome());
			player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), s.sound(), SoundSource.PLAYERS,
					s.volume(), s.pitch(), false);
			played = s.sound();
		}
		return new Shown(lines, notice, played);
	}

	/**
	 * Lead decision (ARCHITECTURE §6.4): the full title when {@code font.width(full) * 4 <= guiWidth - 8}, the subtitle
	 * then being only the outcome line (or none); otherwise the short title with the source name in the subtitle,
	 * followed by " · " and the outcome line for special outcomes. A subtitle wider than the screen at 2x keeps only the
	 * name (or nothing on the full-title path) and the outcome line moves to the end of the actionbar instead.
	 */
	public static Lines compose(Component sourceName, Component actionbar, MutationRoll.Outcome outcome, Font font, int guiWidth) {
		Component special = outcome.isSpecial() ? Component.translatable(KEY + "subtitle." + outcome.name().toLowerCase(Locale.ROOT)) : null;
		Component full = Component.translatable(KEY + "title", sourceName);
		boolean fullTitle = fits(font, full, TITLE_SCALE, guiWidth);
		Component title = fullTitle ? full : Component.translatable(KEY + "title.short");
		Component base = fullTitle ? null : sourceName;
		if (special == null) return new Lines(title, base, actionbar);
		Component subtitle = base == null ? special : joined(base, special);
		if (fits(font, subtitle, SUBTITLE_SCALE, guiWidth)) return new Lines(title, subtitle, actionbar);
		return new Lines(title, base, joined(actionbar, special));
	}

	/** "a · b" through {@code absorbaholic.absorbed.subtitle.joined} (the separator is translatable too). */
	private static Component joined(Component a, Component b) {
		return Component.translatable(KEY + "subtitle.joined", a, b);
	}

	/** Whether {@code text} fits the screen when drawn at {@code scale}. */
	public static boolean fits(Font font, Component text, int scale, int guiWidth) {
		return font.width(text) * scale <= guiWidth - TITLE_MARGIN;
	}

	public static OutcomeSound soundOf(MutationRoll.Outcome outcome) {
		return switch (outcome) {
			case NORMAL -> new OutcomeSound(SoundEvents.PLAYER_LEVELUP, 0.6F, 1.4F);
			case MUTATE_TRAIT, MUTATE_WEAKNESS -> new OutcomeSound(SoundEvents.TOTEM_USE, 0.5F, 1.2F);
			case PURE -> new OutcomeSound(SoundEvents.BEACON_ACTIVATE, 0.8F, 1.6F);
		};
	}
}
