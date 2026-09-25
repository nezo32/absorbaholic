package dev.absorbaholic.absorb;

import net.minecraft.resources.Identifier;

/**
 * A running absorb channel of one player (stored in {@code PlayerRuntime.channel}): the target, the source it
 * resolved to at start, the game time it started and the last game time the client confirmed it still holds the key.
 */
public record AbsorbChannel(AbsorbTarget target, Identifier source, long startTick, long lastHeldTick) {
	public AbsorbChannel held(long now) {
		return new AbsorbChannel(target, source, startTick, now);
	}

	public int elapsed(long now) {
		return (int) (now - startTick);
	}
}
