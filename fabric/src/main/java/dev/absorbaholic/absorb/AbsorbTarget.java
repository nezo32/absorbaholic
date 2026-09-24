package dev.absorbaholic.absorb;

import dev.absorbaholic.net.NetCodecs;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the client asks to absorb: a block at {@code pos}, the fluid at {@code pos} (its source block is consumed),
 * or the living entity {@code entityId} ({@code pos} unused then). The server re-validates everything every tick.
 */
public record AbsorbTarget(Kind kind, BlockPos pos, int entityId) {
	public enum Kind { BLOCK, FLUID, ENTITY }

	public static final StreamCodec<ByteBuf, AbsorbTarget> STREAM_CODEC = StreamCodec.composite(
			NetCodecs.enumCodec(Kind.class), AbsorbTarget::kind,
			BlockPos.STREAM_CODEC, AbsorbTarget::pos,
			ByteBufCodecs.VAR_INT, AbsorbTarget::entityId,
			AbsorbTarget::new);

	public static AbsorbTarget block(BlockPos pos) {
		return new AbsorbTarget(Kind.BLOCK, pos.immutable(), -1);
	}

	public static AbsorbTarget fluid(BlockPos pos) {
		return new AbsorbTarget(Kind.FLUID, pos.immutable(), -1);
	}

	public static AbsorbTarget entity(int entityId) {
		return new AbsorbTarget(Kind.ENTITY, BlockPos.ZERO, entityId);
	}
}
