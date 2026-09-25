package dev.absorbaholic.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.absorbaholic.net.AuraPayload;
import dev.absorbaholic.net.ChannelStatePayload;
import dev.absorbaholic.net.WorldStatePayload;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.registry.SourceMatcher;
import dev.absorbaholic.registry.SourceSummary;
import dev.absorbaholic.trait.MovementState;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Everything the client knows from the server, written only by {@code ClientNetworking} (payload handlers, render
 * thread) and read by the HUD, screens, aura and input code (render thread). Reset on disconnect.
 * {@link #serverHasMod()} is false until the first {@code WorldStatePayload} arrives (vanilla / mod-less server).
 */
public final class ClientState {
	private static SourceMatcher<SourceSummary> sources = SourceMatcher.empty();
	private static @Nullable WorldStatePayload world;
	private static final Set<Identifier> discovered = new HashSet<>();
	private static PlayerTraits ownTraits = PlayerTraits.EMPTY;
	private static final Map<Integer, AuraPayload> auras = new HashMap<>();
	private static @Nullable ChannelStatePayload channel;
	private static MovementState movement = MovementState.NONE;
	private static long channelReceivedAt;

	private ClientState() {}

	public static void reset() {
		sources = SourceMatcher.empty();
		world = null;
		discovered.clear();
		ownTraits = PlayerTraits.EMPTY;
		auras.clear();
		channel = null;
		movement = MovementState.NONE;
	}

	/** Last movement state from the server; re-apply it to a new LocalPlayer (respawn / dimension change). */
	public static MovementState movement() {
		return movement;
	}

	public static void setMovement(MovementState state) {
		movement = state;
	}

	public static boolean serverHasMod() {
		return world != null;
	}

	/** World mode ON as last reported by the server (false without the mod on the server). */
	public static boolean modeEnabled() {
		return world != null && world.enabled();
	}

	public static boolean hintsEnabled() {
		return world != null && world.hints();
	}

	public static @Nullable WorldStatePayload world() {
		return world;
	}

	public static void setWorld(WorldStatePayload state) {
		world = state;
	}

	public static SourceMatcher<SourceSummary> sources() {
		return sources;
	}

	public static void setSources(List<SourceSummary> list) {
		sources = new SourceMatcher<>(list);
	}

	public static boolean isDiscovered(Identifier source) {
		return discovered.contains(source);
	}

	public static void setDiscovered(boolean replace, List<Identifier> ids) {
		if (replace) discovered.clear();
		discovered.addAll(ids);
	}

	public static PlayerTraits ownTraits() {
		return ownTraits;
	}

	public static void setOwnTraits(PlayerTraits traits) {
		ownTraits = traits;
	}

	public static @Nullable AuraPayload aura(int entityId) {
		return auras.get(entityId);
	}

	public static Map<Integer, AuraPayload> auras() {
		return auras;
	}

	public static void setAura(AuraPayload aura) {
		if (aura.strength() <= 0) {
			auras.remove(aura.entityId());
		} else {
			auras.put(aura.entityId(), aura);
		}
	}

	public static @Nullable ChannelStatePayload channel() {
		return channel;
	}

	/** Client game time when the last channel state arrived (to interpolate the progress ring). */
	public static long channelReceivedAt() {
		return channelReceivedAt;
	}

	public static void setChannel(@Nullable ChannelStatePayload state, long clientTick) {
		channel = state;
		channelReceivedAt = clientTick;
	}
}
