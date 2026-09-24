package dev.absorbaholic.client;

import java.util.function.BiConsumer;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.client.input.AbsorbInput;
import dev.absorbaholic.client.notify.NotifyClient;
import dev.absorbaholic.net.AbsorbedPayload;
import dev.absorbaholic.net.AuraPayload;
import dev.absorbaholic.net.ChannelStatePayload;
import dev.absorbaholic.net.DiscoveredPayload;
import dev.absorbaholic.net.MovementPayload;
import dev.absorbaholic.net.SourcesSyncPayload;
import dev.absorbaholic.net.TraitsPayload;
import dev.absorbaholic.net.WorldStatePayload;
import dev.absorbaholic.trait.MovementFlagsHolder;
import dev.absorbaholic.trait.MovementState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Client receivers of every S2C payload (they run on the render thread), all writing into {@link ClientState}:
 * sources, world state, discovered set, own traits, absorb feedback ({@link NotifyClient}), channel state, movement
 * state and auras. The movement state is re-applied whenever the LocalPlayer is recreated (respawn, dimension change).
 * Disconnecting or (re)configuring resets everything; an aura is dropped when its entity unloads. A
 * {@code TraitsPayload} with {@code openScreen} goes to the screen opener that the UI package installs with
 * {@link #setTraitsScreenOpener}.
 */
public final class ClientNetworking {
	private static @Nullable BiConsumer<Minecraft, TraitsPayload> traitsScreenOpener;

	private ClientNetworking() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(SourcesSyncPayload.TYPE, (p, ctx) -> ClientState.setSources(p.sources()));
		ClientPlayNetworking.registerGlobalReceiver(WorldStatePayload.TYPE, (p, ctx) -> ClientState.setWorld(p));
		ClientPlayNetworking.registerGlobalReceiver(DiscoveredPayload.TYPE, (p, ctx) -> ClientState.setDiscovered(p.replace(), p.sources()));
		ClientPlayNetworking.registerGlobalReceiver(TraitsPayload.TYPE, (p, ctx) -> onTraits(p, ctx.client()));
		ClientPlayNetworking.registerGlobalReceiver(AbsorbedPayload.TYPE, (p, ctx) -> NotifyClient.handle(p, ctx.client()));
		ClientPlayNetworking.registerGlobalReceiver(ChannelStatePayload.TYPE, (p, ctx) -> ClientState.setChannel(p, clientTime(ctx.client())));
		ClientPlayNetworking.registerGlobalReceiver(MovementPayload.TYPE, (p, ctx) -> {
			ClientState.setMovement(p.state());
			applyMovement(ctx.client().player);
		});
		ClientPlayNetworking.registerGlobalReceiver(AuraPayload.TYPE, (p, ctx) -> ClientState.setAura(p));

		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> client.execute(() -> resetAll(client)));
		// (Re)configuration on the same connection (e.g. a proxy switching backends) never fires DISCONNECT: the next
		// backend may be vanilla, so nothing of the previous one (movement physics, traits, auras) may survive it.
		ClientConfigurationConnectionEvents.START.register((listener, client) -> client.execute(() -> resetAll(client)));
		// The server only sends aura changes to trackers: forget an aura when its entity leaves this client, so an
		// outdated one cannot come back when the entity is tracked again. The own player keeps its entry (respawn and
		// dimension change unload the old LocalPlayer, and the server only resends the aura on change).
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (!(entity instanceof LocalPlayer)) ClientState.auras().remove(entity.getId());
		});
		// The client matcher resolves tags lazily; forget cached lookups when the server's tags arrive.
		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
			if (client) ClientState.sources().clearCache();
		});
		// A respawn or dimension change creates a new LocalPlayer with no movement state: re-apply the last one.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> applyMovement(mc.player));
	}

	/**
	 * Installs what opens the traits screen for a {@code TraitsPayload} with {@code openScreen} (the UI package calls this
	 * from its {@code register()}). Called on the render thread.
	 */
	public static void setTraitsScreenOpener(BiConsumer<Minecraft, TraitsPayload> opener) {
		traitsScreenOpener = opener;
	}

	private static void onTraits(TraitsPayload payload, Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player != null && player.getUUID().equals(payload.owner())) ClientState.setOwnTraits(payload.traits());
		if (!payload.openScreen()) return;
		BiConsumer<Minecraft, TraitsPayload> opener = traitsScreenOpener;
		if (opener == null) {
			Absorbaholic.LOGGER.debug("No traits screen installed; ignoring the request to show {}'s traits", payload.ownerName());
			return;
		}
		opener.accept(mc, payload);
	}

	/** Forgets everything the previous server told us, including the movement physics on the current player. */
	public static void resetAll(Minecraft mc) {
		ClientState.reset();
		AbsorbInput.reset();
		applyMovement(mc.player);
	}

	private static long clientTime(Minecraft mc) {
		return mc.level != null ? mc.level.getGameTime() : 0L;
	}

	private static void applyMovement(@Nullable LocalPlayer player) {
		if (player == null) return;
		MovementFlagsHolder holder = (MovementFlagsHolder) player;
		MovementState state = ClientState.movement();
		if (!state.equals(holder.absorbaholic$movement())) holder.absorbaholic$setMovement(state);
	}
}
