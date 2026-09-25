package dev.absorbaholic.trait;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Client-physics state granted by behaviors: {@link MovementFlags} plus the parameters the client needs to simulate
 * them ({@code climb_walls} speed, {@code sink_in_water} speed). Merged over all active entries (flags OR-ed, speeds
 * max), stored on both sides through {@link MovementFlagsHolder}, synced in {@code MovementPayload}.
 */
public record MovementState(int flags, float climbSpeed, float sinkSpeed) {
	public static final MovementState NONE = new MovementState(0, 0.0F, 0.0F);

	public static final StreamCodec<ByteBuf, MovementState> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, MovementState::flags,
			ByteBufCodecs.FLOAT, MovementState::climbSpeed,
			ByteBufCodecs.FLOAT, MovementState::sinkSpeed,
			MovementState::new);

	public static MovementState of(int flags) {
		return new MovementState(flags, 0.0F, 0.0F);
	}

	public boolean has(int flag) {
		return (flags & flag) != 0;
	}

	/** This state without the {@code flags} bits. */
	public MovementState without(int flags) {
		return (this.flags & flags) == 0 ? this : new MovementState(this.flags & ~flags, climbSpeed, sinkSpeed);
	}

	public MovementState merge(MovementState other) {
		if (other == NONE) return this;
		if (this == NONE) return other;
		return new MovementState(flags | other.flags, Math.max(climbSpeed, other.climbSpeed), Math.max(sinkSpeed, other.sinkSpeed));
	}
}
