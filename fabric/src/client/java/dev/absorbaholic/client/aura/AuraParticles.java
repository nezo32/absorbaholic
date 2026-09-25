package dev.absorbaholic.client.aura;

import dev.absorbaholic.client.ClientState;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.ColorMix;
import dev.absorbaholic.net.AuraPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;

/**
 * Subtle dust particles around players that have an aura ({@code ClientState.auras()}, sent by the server: the color
 * is the {@link ColorMix} of their sources' colors weighted by trait level, strength grows with total levels). Every
 * {@link AbsorbCaps#AURA_PARTICLE_INTERVAL_TICKS} ticks (staggered per player) 1..3 particles, each slightly
 * whitened for a shimmer. Skipped for invisible / spectator / dead players and players farther than
 * {@link #MAX_DISTANCE} blocks. Yourself in first person: a third of the rate, only around your feet, so the view
 * stays clear. {@code addParticle} honours the vanilla Particles option.
 */
public final class AuraParticles {
	/** No particles for players farther than this from the local player (blocks). */
	public static final double MAX_DISTANCE = AbsorbCaps.AURA_PARTICLE_MAX_DISTANCE;
	/** Rate divisor for your own aura while in first person. */
	private static final int FIRST_PERSON_RATE_DIVISOR = 3;
	/** Up to this share of white is mixed into each particle's color. */
	private static final double SHIMMER = 0.3;

	private static long ticks;

	private AuraParticles() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(AuraParticles::tick);
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || mc.isPaused() || ClientState.auras().isEmpty()) return;
		ticks++;
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		RandomSource random = level.getRandom();
		for (AbstractClientPlayer player : level.players()) {
			AuraPayload aura = ClientState.aura(player.getId());
			if (aura == null || player.isInvisible() || player.isSpectator() || !player.isAlive()) continue;
			boolean self = player == mc.player;
			if (!self && player.distanceToSqr(mc.player) > MAX_DISTANCE * MAX_DISTANCE) continue;
			boolean selfFirstPerson = self && firstPerson;
			int interval = AbsorbCaps.AURA_PARTICLE_INTERVAL_TICKS * (selfFirstPerson ? FIRST_PERSON_RATE_DIVISOR : 1);
			if (Math.floorMod(ticks + player.getId(), interval) != 0) continue;
			spawn(level, player, aura, selfFirstPerson, random);
		}
	}

	private static void spawn(ClientLevel level, AbstractClientPlayer player, AuraPayload aura, boolean feetOnly, RandomSource random) {
		float strength = Math.clamp(aura.strength(), 0.0F, 1.0F);
		int count = feetOnly ? 1 : 1 + Math.round(strength * 2);
		float scale = 0.7F + 0.4F * strength;
		for (int i = 0; i < count; i++) {
			int color = ColorMix.mix(new int[] {aura.color() & 0xFFFFFF, 0xFFFFFF}, new double[] {1.0, random.nextDouble() * SHIMMER});
			double y = feetOnly ? player.getY() + random.nextDouble() * 0.4 : player.getRandomY();
			level.addParticle(new DustParticleOptions(color, scale),
					player.getRandomX(0.7), y, player.getRandomZ(0.7), 0.0, 0.02, 0.0);
		}
	}
}
