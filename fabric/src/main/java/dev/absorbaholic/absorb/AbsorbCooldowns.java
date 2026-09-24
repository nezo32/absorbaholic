package dev.absorbaholic.absorb;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerRuntime;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps cooldown deadlines across relog and death (the {@code absorbaholic:runtime} attachment is transient, so a new
 * player entity would otherwise start with none): the absorb cooldown ({@code absorbCooldownUntil}) and the engine's
 * ability cooldowns ({@code abilityCooldowns}), both overworld game-time deadlines.
 * <ul>
 * <li>Death / End exit: {@code ServerPlayerEvents.COPY_FROM} copies the old runtime's deadlines into the new one.</li>
 * <li>Relog: on DISCONNECT the still-running deadlines are parked in a server-lifetime map keyed by player UUID; JOIN
 *     restores them. Expired deadlines are dropped, and the map is cleared when the server stops (a restart takes
 *     longer than any cooldown anyway).</li>
 * </ul>
 * Restoring keeps the later of the stored and current deadline, so it never shortens a cooldown. Server thread only.
 */
public final class AbsorbCooldowns {
	/** Parked deadlines of disconnected players. */
	private static final Map<UUID, Deadlines> PARKED = new HashMap<>();

	private AbsorbCooldowns() {}

	/** Deadlines of one player. */
	record Deadlines(long absorbUntil, Map<Identifier, Long> abilities) {
		static Deadlines of(PlayerRuntime runtime, long now) {
			Map<Identifier, Long> abilities = new HashMap<>();
			runtime.abilityCooldowns.forEach((id, until) -> {
				if (until != null && until > now) abilities.put(id, until);
			});
			return new Deadlines(runtime.absorbCooldownUntil > now ? runtime.absorbCooldownUntil : 0L, Map.copyOf(abilities));
		}

		boolean isEmpty() {
			return absorbUntil == 0L && abilities.isEmpty();
		}

		void applyTo(PlayerRuntime runtime) {
			runtime.absorbCooldownUntil = Math.max(runtime.absorbCooldownUntil, absorbUntil);
			abilities.forEach((id, until) -> runtime.abilityCooldowns.merge(id, until, Math::max));
		}
	}

	static void register() {
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> copy(oldPlayer, newPlayer));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> park(handler.player));
		ServerPlayerEvents.JOIN.register(AbsorbCooldowns::restore);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PARKED.clear());
	}

	/** Death or End exit: carry the old entity's running deadlines over to the new one. */
	public static void copy(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
		PlayerRuntime old = oldPlayer.getAttached(PlayerData.RUNTIME);
		if (old == null) return;
		Deadlines deadlines = Deadlines.of(old, AbsorbHandler.now(newPlayer.level().getServer()));
		if (!deadlines.isEmpty()) deadlines.applyTo(PlayerData.runtime(newPlayer));
	}

	/** The player leaves: park its running deadlines. */
	public static void park(ServerPlayer player) {
		PlayerRuntime runtime = player.getAttached(PlayerData.RUNTIME);
		long now = AbsorbHandler.now(player.level().getServer());
		PARKED.values().removeIf(d -> d.absorbUntil() <= now && d.abilities().values().stream().allMatch(until -> until <= now));
		if (runtime == null) return;
		Deadlines deadlines = Deadlines.of(runtime, now);
		if (deadlines.isEmpty()) {
			PARKED.remove(player.getUUID());
		} else {
			PARKED.put(player.getUUID(), deadlines);
		}
	}

	/** The player (re)joins: restore its parked deadlines. */
	public static void restore(ServerPlayer player) {
		Deadlines deadlines = PARKED.remove(player.getUUID());
		if (deadlines != null) deadlines.applyTo(PlayerData.runtime(player));
	}
}
