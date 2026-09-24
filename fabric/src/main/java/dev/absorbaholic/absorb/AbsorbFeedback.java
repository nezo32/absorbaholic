package dev.absorbaholic.absorb;

import java.util.List;
import java.util.Optional;

import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.MutationRoll;
import dev.absorbaholic.net.AbsorbedPayload;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.text.Texts;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Everything players see and hear around absorbing.
 * <ul>
 * <li>{@link #absorbed}: a modded client gets one {@link AbsorbedPayload} and composes title, subtitle, actionbar,
 *     notice and sound itself according to its notification settings; a vanilla client gets the short title, the
 *     source name (plus the special line) as subtitle, the actionbar, the notice and the sound as plain packets, all
 *     built with {@link Texts#tr} (English fallback). Particles at the player are server-spawned for everyone, and a
 *     mutation or pure absorption is also broadcast in chat to every player.</li>
 * <li>{@link #refused}: a red actionbar line (only modded clients can channel).</li>
 * <li>{@link #channelTick}: particles flying from the target into the player and a rising chime.</li>
 * </ul>
 */
public final class AbsorbFeedback {
	public static final String REFUSE_DISABLED = "absorbaholic.refuse.disabled";
	public static final String REFUSE_COOLDOWN = "absorbaholic.refuse.cooldown";
	public static final String REFUSE_PROTECTED = "absorbaholic.refuse.protected";
	public static final String REFUSE_CONTAINER = "absorbaholic.refuse.container_not_empty";
	public static final String REFUSE_MOB_HEALTH = "absorbaholic.refuse.mob_health";
	public static final String REFUSE_MAX_LEVEL = "absorbaholic.refuse.max_level";

	public static final String TITLE_SHORT = "absorbaholic.absorbed.title.short";
	public static final String ACTIONBAR = "absorbaholic.absorbed.actionbar";
	public static final String ACTIONBAR_PURE = "absorbaholic.absorbed.actionbar.pure";
	public static final String EVICTED = "absorbaholic.message.evicted";

	/** Title timing of the vanilla fallback (fade in, stay, fade out). */
	public static final int TITLE_FADE_IN = 10;
	public static final int TITLE_STAY = 50;
	public static final int TITLE_FADE_OUT = 15;
	/** Enchant particles per channel effect tick, flying from the target into the player. */
	private static final int CHANNEL_PARTICLES = 6;

	/** A sound with its volume and pitch. */
	public record Cue(SoundEvent sound, float volume, float pitch) {}

	private AbsorbFeedback() {}

	// ---- absorbed ----------------------------------------------------------------------------------------------

	/** The sound of an absorption with {@code outcome} (common code: the modded client plays the same cue). */
	public static Cue cue(MutationRoll.Outcome outcome) {
		return switch (outcome) {
			case NORMAL -> new Cue(SoundEvents.PLAYER_LEVELUP, 0.6F, 1.4F);
			case MUTATE_TRAIT, MUTATE_WEAKNESS -> new Cue(SoundEvents.TOTEM_USE, 0.5F, 1.2F);
			case PURE -> new Cue(SoundEvents.BEACON_ACTIVATE, 0.8F, 1.6F);
		};
	}

	/** Special subtitle line of a mutation / pure outcome ({@code absorbaholic.absorbed.subtitle.*}), else empty. */
	public static Optional<Component> specialLine(MutationRoll.Outcome outcome) {
		return switch (outcome) {
			case NORMAL -> Optional.empty();
			case MUTATE_TRAIT -> Optional.of(Texts.tr("absorbaholic.absorbed.subtitle.mutate_trait").withStyle(ChatFormatting.LIGHT_PURPLE));
			case MUTATE_WEAKNESS -> Optional.of(Texts.tr("absorbaholic.absorbed.subtitle.mutate_weakness").withStyle(ChatFormatting.DARK_PURPLE));
			case PURE -> Optional.of(Texts.tr("absorbaholic.absorbed.subtitle.pure").withStyle(ChatFormatting.AQUA));
		};
	}

	/** "&lt;trait&gt; &lt;level&gt; · &lt;weakness&gt; &lt;level&gt;", or the pure variant while the weakness is level 0. */
	public static Component actionbar(SourceDefinition source, int traitLevel, int weaknessLevel) {
		Component trait = traitName(source).copy().withStyle(ChatFormatting.GREEN);
		if (weaknessLevel <= 0) return Texts.tr(ACTIONBAR_PURE, trait, level(traitLevel));
		Component weakness = Texts.tr(source.weaknessLangKey()).withStyle(ChatFormatting.RED);
		return Texts.tr(ACTIONBAR, trait, level(traitLevel), weakness, level(weaknessLevel));
	}

	/** The chat notice about evicted sources, if any. */
	public static Optional<Component> notice(List<Identifier> evicted) {
		if (evicted.isEmpty()) return Optional.empty();
		MutableComponent names = Component.empty();
		for (int i = 0; i < evicted.size(); i++) {
			if (i > 0) names.append(", ");
			Identifier id = evicted.get(i);
			names.append(SourceRegistry.byId(id).map(AbsorbFeedback::traitName).orElseGet(() -> Component.literal(id.toString())));
		}
		return Optional.of(Texts.tr(EVICTED, names).withStyle(ChatFormatting.GRAY));
	}

	/** Feedback for a completed absorption; the payload for clients with the mod, plain packets otherwise. */
	public static void absorbed(ServerPlayer player, Component sourceName, SourceDefinition source, AbsorbService.Result result) {
		absorbed(player, sourceName, source, result, ServerPlayNetworking.canSend(player, AbsorbedPayload.TYPE));
	}

	/** {@code modded} = the client has the {@code absorbaholic:absorbed} channel. Public for gametests. */
	public static void absorbed(ServerPlayer player, Component sourceName, SourceDefinition source, AbsorbService.Result result,
			boolean modded) {
		MutationRoll.Outcome outcome = result.outcome();
		Component actionbar = actionbar(source, result.traitLevel(), result.weaknessLevel());
		Optional<Component> notice = notice(result.evicted());
		if (modded) {
			ServerPlayNetworking.send(player, new AbsorbedPayload(sourceName, actionbar, outcome, notice));
		} else {
			MutableComponent subtitle = sourceName.copy();
			specialLine(outcome).ifPresent(line -> subtitle.append(" · ").append(line));
			player.connection.send(new ClientboundSetTitlesAnimationPacket(TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			player.connection.send(new ClientboundSetTitleTextPacket(Texts.tr(TITLE_SHORT)));
			player.sendOverlayMessage(actionbar);
			notice.ifPresent(player::sendSystemMessage);
			Cue cue = cue(outcome);
			player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(cue.sound()),
					SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(), cue.volume(), cue.pitch(), player.getRandom().nextLong()));
		}
		burst(player, outcome);
		announce(player, sourceName, outcome);
	}

	/** Particles at the player, visible to everyone nearby. */
	private static void burst(ServerPlayer player, MutationRoll.Outcome outcome) {
		ParticleOptions particle = switch (outcome) {
			case NORMAL -> ParticleTypes.HAPPY_VILLAGER;
			case MUTATE_TRAIT, MUTATE_WEAKNESS -> ParticleTypes.TOTEM_OF_UNDYING;
			case PURE -> ParticleTypes.END_ROD;
		};
		int count = outcome.isSpecial() ? 40 : 20;
		player.level().sendParticles(particle, player.getX(), player.getY() + player.getBbHeight() * 0.6, player.getZ(),
				count, 0.5, 0.7, 0.5, outcome.isSpecial() ? 0.25 : 0.05);
	}

	/** Mutations and pure absorptions are announced loudly: a chat line to every player. */
	private static void announce(ServerPlayer player, Component sourceName, MutationRoll.Outcome outcome) {
		String key = switch (outcome) {
			case NORMAL -> null;
			case MUTATE_TRAIT -> "absorbaholic.announce.mutate_trait";
			case MUTATE_WEAKNESS -> "absorbaholic.announce.mutate_weakness";
			case PURE -> "absorbaholic.announce.pure";
		};
		if (key == null) return;
		ChatFormatting color = outcome == MutationRoll.Outcome.PURE ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE;
		player.level().getServer().getPlayerList().broadcastSystemMessage(
				Texts.tr(key, player.getDisplayName(), sourceName).withStyle(color), false);
	}

	// ---- refusals ----------------------------------------------------------------------------------------------

	/** A refusal reason ({@code absorbaholic.refuse.*}), red. */
	public static Component reason(String key, Object... args) {
		return Texts.tr(key, args).withStyle(ChatFormatting.RED);
	}

	/** Shows a refusal on the player's actionbar. */
	public static void refused(ServerPlayer player, Component reason) {
		player.sendOverlayMessage(reason);
	}

	// ---- channel -----------------------------------------------------------------------------------------------

	/**
	 * Channel effects at {@code elapsed} ticks: enchant particles flying from {@code from} (the target) into the
	 * player, and a chime whose pitch rises from 0.6 to 1.6 over the channel, audible nearby.
	 */
	public static void channelTick(ServerPlayer player, Vec3 from, int elapsed) {
		ServerLevel level = player.level();
		RandomSource random = player.getRandom();
		double x = player.getX();
		double y = player.getY() + player.getBbHeight() * 0.6;
		double z = player.getZ();
		for (int i = 0; i < CHANNEL_PARTICLES; i++) {
			// count 0: the client spawns one particle at (x, y, z) with "velocity" (dx, dy, dz); an enchant particle
			// starts at position + velocity and flies to the position, so these converge on the player
			double dx = from.x + (random.nextDouble() - 0.5) * 0.8 - x;
			double dy = from.y + (random.nextDouble() - 0.5) * 0.8 - y;
			double dz = from.z + (random.nextDouble() - 0.5) * 0.8 - z;
			level.sendParticles(ParticleTypes.ENCHANT, x, y, z, 0, dx, dy, dz, 1.0);
		}
		float progress = Math.min(1.0F, elapsed / (float) AbsorbCaps.CHANNEL_TICKS);
		level.playSound(null, x, player.getY(), z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 0.6F + progress);
	}

	// ---- names -------------------------------------------------------------------------------------------------

	/** The display name of {@code source} ({@code nameKey}: a vanilla block / entity name or {@code absorbaholic.source.*}). */
	public static Component sourceName(SourceDefinition source) {
		return Texts.tr(source.nameKey());
	}

	/** The trait name of {@code source} ({@code absorbaholic.trait.<key>}). */
	public static Component traitName(SourceDefinition source) {
		return Texts.tr(source.traitLangKey());
	}

	/** A level numeral ({@code enchantment.level.N}, vanilla). */
	public static Component level(int level) {
		return Component.translatable("enchantment.level." + level);
	}
}
