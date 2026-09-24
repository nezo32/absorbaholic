package dev.absorbaholic.absorb;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jspecify.annotations.Nullable;

/**
 * Removes an absorbed block without drops, experience or container spill (flags 2|16|32|256, research §10): the
 * other half of a two-part block (doors, tall plants, beds) goes with it, and only afterwards do the neighbours get
 * their updates (so the partner is never "broken" with loot). A waterlogged block leaves its water behind; an
 * absorbed fluid source becomes air.
 */
public final class BlockRemoval {
	/** UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SUPPRESS_DROPS | UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS. */
	public static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	private BlockRemoval() {}

	/**
	 * Removes the block at {@code pos} (and its partner half) silently.
	 *
	 * @param fluid true when the fluid source itself was absorbed: the position becomes air; otherwise the block is
	 *     replaced by its own fluid (air, or water for a waterlogged block)
	 */
	public static void removeSilently(ServerLevel level, BlockPos pos, boolean fluid) {
		BlockState state = level.getBlockState(pos);
		List<BlockPos> parts = new ArrayList<>(2);
		parts.add(pos.immutable());
		BlockPos partner = partner(state, pos);
		if (partner != null && level.getBlockState(partner).is(state.getBlock())) parts.add(partner);

		for (BlockPos p : parts) {
			BlockState replacement = p.equals(pos) && fluid
					? Blocks.AIR.defaultBlockState()
					: level.getBlockState(p).getFluidState().createLegacyBlock();
			level.setBlock(p, replacement, FLAGS);
		}
		for (BlockPos p : parts) {
			level.updateNeighborsAt(p, state.getBlock());
			level.getBlockState(p).updateNeighbourShapes(level, p, Block.UPDATE_ALL);
		}
	}

	/** The other half of a two-part block, or null. */
	static @Nullable BlockPos partner(BlockState state, BlockPos pos) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
		}
		if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING); // foot → head is FACING
			return state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite());
		}
		return null;
	}
}
