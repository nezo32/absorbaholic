package dev.absorbaholic.player;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.text.Texts;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.world.AbsorbWorldSettings;
import dev.absorbaholic.world.WorldStateSync;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Death, respawn and join handling of the {@code absorbaholic:traits} attachment (which is persistent but not
 * copyOnDeath, so this class alone decides what a respawned player keeps):
 * <ul>
 * <li>{@code AFTER_DEATH}: if the dying player's cached active set wipes on death (dragon egg, see
 *     {@link TraitEngine#wipesOnDeath}), every trait is removed right away, with a chat line and a sound. Doing it at
 *     death time (not at respawn) matters: the engine turns a dead player dormant on its next tick, and the wipe is
 *     saved even if the player logs out on the death screen. A dormant egg (mode OFF, creative) never wipes.</li>
 * <li>{@code COPY_FROM(old, new, alive)}: {@code alive} (leaving the End) always copies. A death copies when the world
 *     keeps traits on death, or when Absorbaholic Mode is OFF (dormant traits are never deleted by a world rule);
 *     otherwise the traits are lost with a chat line. The old player's wipe flag is checked again defensively.</li>
 * <li>{@code AFTER_RESPAWN} (late phase, after Fabric's own attachment transfer) and {@code JOIN}: mark the engine
 *     dirty (vanilla drops transient attribute modifiers on respawn) and send traits, world state and discovered set
 *     to modded clients.</li>
 * </ul>
 * Server thread only; no exception escapes into vanilla code.
 */
public final class PlayerLifecycle {
	/** AFTER_DEATH phase before the default one: wipe before any death hook could touch the cached active set. */
	private static final Identifier EARLY = Absorbaholic.id("early");
	/** AFTER_RESPAWN phase after the default one, where Fabric transfers attachments on an End exit. */
	private static final Identifier LATE = Absorbaholic.id("late");

	private PlayerLifecycle() {}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
		ServerLivingEntityEvents.AFTER_DEATH.register(EARLY, (entity, source) -> {
			if (entity instanceof ServerPlayer player) safely("death", player, () -> onDeath(player));
		});
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) ->
				safely("respawn copy", newPlayer, () -> copyFrom(oldPlayer, newPlayer, alive)));
		ServerPlayerEvents.AFTER_RESPAWN.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
		ServerPlayerEvents.AFTER_RESPAWN.register(LATE, (oldPlayer, newPlayer, alive) ->
				safely("respawn", newPlayer, () -> refresh(newPlayer)));
		ServerPlayerEvents.JOIN.register(player -> safely("join", player, () -> refresh(player)));
	}

	/** AFTER_DEATH: the wipe-on-death rule (dragon egg), evaluated on the still-cached active set. */
	public static void onDeath(ServerPlayer player) {
		if (player instanceof FakePlayer || PlayerData.traits(player).isEmpty()) return;
		if (!TraitEngine.wipesOnDeath(player)) return;
		PlayerData.setTraits(player, PlayerTraits.EMPTY);
		player.sendSystemMessage(Texts.tr("absorbaholic.message.wiped"));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 0.6F, 1.4F);
		Absorbaholic.LOGGER.info("Wiped all Absorbaholic traits of {} on death", player.getScoreboardName());
	}

	/**
	 * COPY_FROM: decides what the new player entity inherits. The attachment is set directly (not through
	 * {@code PlayerData.setTraits}): from the player's point of view nothing changed, and the new entity is not in a
	 * level yet. {@link #refresh} syncs it once the respawn is complete.
	 */
	public static void copyFrom(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		PlayerTraits traits = PlayerData.traits(oldPlayer);
		if (traits.isEmpty()) return;
		if (alive) {
			newPlayer.setAttached(PlayerData.TRAITS, traits);
			return;
		}
		if (TraitEngine.wipesOnDeath(oldPlayer)) {
			newPlayer.sendSystemMessage(Texts.tr("absorbaholic.message.wiped"));
			return;
		}
		AbsorbWorldSettings settings = AbsorbWorldSettings.get(newPlayer.level().getServer());
		if (settings.keepOnDeath() || !settings.enabled()) {
			newPlayer.setAttached(PlayerData.TRAITS, traits);
		} else {
			newPlayer.sendSystemMessage(Texts.tr("absorbaholic.message.lost"));
		}
	}

	/** Respawn / join: rebuild the engine state and bring a modded client up to date. */
	public static void refresh(ServerPlayer player) {
		TraitEngine.markDirty(player);
		TraitsSync.send(player);
		WorldStateSync.sendAll(player);
	}

	private static void safely(String what, ServerPlayer player, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			Absorbaholic.LOGGER.error("Absorbaholic {} handling failed for {}", what, player.getScoreboardName(), e);
		}
	}
}
