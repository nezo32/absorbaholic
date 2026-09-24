package dev.absorbaholic.client.aura;

/**
 * WP-UI. END_CLIENT_TICK: for each player with an aura in {@code ClientState.auras()} (not invisible / spectator, not
 * yourself in first person), every {@code AbsorbCaps.AURA_PARTICLE_INTERVAL_TICKS} a few {@code DustParticleOptions}
 * in the aura color, count scaled by strength. Respects the vanilla particles option (addParticle).
 */
public final class AuraParticles {
	private AuraParticles() {}

	public static void register() {
		// TODO(WP-UI)
	}
}
