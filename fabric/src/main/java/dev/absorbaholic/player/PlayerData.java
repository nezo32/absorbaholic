package dev.absorbaholic.player;

import dev.absorbaholic.Absorbaholic;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player attachments and their accessors. {@code absorbaholic:traits} is persistent (saved with the player), NOT
 * copyOnDeath: {@code PlayerLifecycle} copies it on respawn according to keep-on-death / dragon egg, and always on the
 * End-exit "respawn". Not synced by Fabric (a client without the mod would be disconnected); we sync with our own
 * payloads instead. {@code absorbaholic:runtime} is transient.
 */
public final class PlayerData {
	public static final AttachmentType<PlayerTraits> TRAITS = AttachmentRegistry.<PlayerTraits>builder()
			.persistent(PlayerTraits.CODEC)
			.initializer(() -> PlayerTraits.EMPTY)
			.buildAndRegister(Absorbaholic.id("traits"));

	public static final AttachmentType<PlayerRuntime> RUNTIME = AttachmentRegistry.<PlayerRuntime>builder()
			.initializer(PlayerRuntime::new)
			.buildAndRegister(Absorbaholic.id("runtime"));

	private PlayerData() {}

	/** Called from Absorbaholic#onInitialize; loads this class so the attachment types register. */
	public static void register() {
		PlayerLifecycle.register();
		TraitsSync.register();
	}

	public static PlayerTraits traits(ServerPlayer player) {
		return player.getAttachedOrElse(TRAITS, PlayerTraits.EMPTY);
	}

	/** Stores {@code traits} and fires {@link TraitsChangedCallback} if they differ. The only way to change traits. */
	public static void setTraits(ServerPlayer player, PlayerTraits traits) {
		PlayerTraits before = traits(player);
		if (before.equals(traits)) return;
		if (traits.isEmpty()) {
			player.removeAttached(TRAITS);
		} else {
			player.setAttached(TRAITS, traits);
		}
		TraitsChangedCallback.EVENT.invoker().onTraitsChanged(player, before, traits);
	}

	public static PlayerRuntime runtime(ServerPlayer player) {
		return player.getAttachedOrCreate(RUNTIME);
	}
}
