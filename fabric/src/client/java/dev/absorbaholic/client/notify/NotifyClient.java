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
 * outcome's subtitle line for mutations and pure absorptions, the actionbar as sent and the notice in chat. Sound ON:
 * the outcome's sound. {@link #handle} is public so the client gametest can call it directly.
 */
public final class NotifyClient {
	/** Title timing (fade in, stay, fade out), the same as the server's vanilla-client fallback. */
	public static final int FADE_IN = 10, STAY = 50, FADE_OUT = 15;
	/** The title is drawn at 4x scale; it must fit the GUI width minus this margin. */
	public static final int TITLE_SCALE = 4, TITLE_MARGIN = 8;
	private static final String KEY = "absorbaholic.absorbed.";
	private static final Component SEPARATOR = Component.literal(" · ");

	private NotifyClient() {}

	/** Title and optional subtitle as composed for one screen width. */
	public record TitleLines(Component title, @Nullable Component subtitle) {}

	/** What {@link #handle} showed or played (null = nothing of that kind); for the client gametest. */
	public record Shown(@Nullable TitleLines title, @Nullable Component actionbar, @Nullable Component notice,
			@Nullable SoundEvent sound) {
		public static final Shown NOTHING = new Shown(null, null, null, null);
	}

	/** Sound, volume and pitch of an outcome (ARCHITECTURE §6.3, same as the vanilla-client fallback). */
	public record OutcomeSound(SoundEvent sound, float volume, float pitch) {}

	public static Shown handle(AbsorbedPayload payload, Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) return Shown.NOTHING;
		NotifySettings settings = NotifyConfig.get();
		TitleLines lines = null;
		Component notice = null;
		if (settings.message()) {
			lines = composeTitle(payload.sourceName(), payload.outcome(), mc.font, mc.getWindow().getGuiScaledWidth());
			Hud hud = mc.gui.hud;
			hud.clearTitles();
			hud.setTimes(FADE_IN, STAY, FADE_OUT);
			if (lines.subtitle() != null) hud.setSubtitle(lines.subtitle());
			hud.setTitle(lines.title());
			player.sendOverlayMessage(payload.actionbar());
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
		return new Shown(lines, settings.message() ? payload.actionbar() : null, notice, played);
	}

	/**
	 * Lead decision (ARCHITECTURE §6.4): the full title when {@code font.width(full) * 4 <= guiWidth - 8}, the subtitle
	 * then being only the outcome line (or none); otherwise the short title with the source name in the subtitle,
	 * followed by " · " and the outcome line for special outcomes.
	 */
	public static TitleLines composeTitle(Component sourceName, MutationRoll.Outcome outcome, Font font, int guiWidth) {
		Component special = outcome.isSpecial() ? Component.translatable(KEY + "subtitle." + outcome.name().toLowerCase(Locale.ROOT)) : null;
		Component full = Component.translatable(KEY + "title", sourceName);
		if (fitsTitle(font, full, guiWidth)) return new TitleLines(full, special);
		Component subtitle = special == null ? sourceName : Component.empty().append(sourceName).append(SEPARATOR).append(special);
		return new TitleLines(Component.translatable(KEY + "title.short"), subtitle);
	}

	/** Whether {@code title} fits the screen at title scale. */
	public static boolean fitsTitle(Font font, Component title, int guiWidth) {
		return font.width(title) * TITLE_SCALE <= guiWidth - TITLE_MARGIN;
	}

	public static OutcomeSound soundOf(MutationRoll.Outcome outcome) {
		return switch (outcome) {
			case NORMAL -> new OutcomeSound(SoundEvents.PLAYER_LEVELUP, 0.6F, 1.4F);
			case MUTATE_TRAIT, MUTATE_WEAKNESS -> new OutcomeSound(SoundEvents.TOTEM_USE, 0.5F, 1.2F);
			case PURE -> new OutcomeSound(SoundEvents.BEACON_ACTIVATE, 0.8F, 1.6F);
		};
	}
}
