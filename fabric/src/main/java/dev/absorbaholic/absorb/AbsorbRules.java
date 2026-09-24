package dev.absorbaholic.absorb;

import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.registry.AbsorbTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Hand / pose / game-mode preconditions and the world-protection rule of absorbing, shared by the server validation,
 * the client's use-key intercept and the HUD hint (common code, no client classes). The world mode, target source
 * and cooldown checks are separate.
 */
public final class AbsorbRules {
	private AbsorbRules() {}

	/** Sneaking, empty main hand, not creative / spectator. Uses the player's own view of its game mode. */
	public static boolean poseAllows(Player player) {
		return player.isShiftKeyDown()
				&& player.getMainHandItem().isEmpty()
				&& !player.isSpectator()
				&& !player.isCreative()
				&& player.isAlive();
	}

	/**
	 * World protection (lead decision): a block in {@code #absorbaholic:bedrock_protected} (bedrock) cannot be absorbed
	 * in the bottom {@link AbsorbCaps#PROTECTED_LAYERS} layers of the dimension, nor at / above
	 * {@code minY + logicalHeight - PROTECTED_LAYERS} in a dimension with a ceiling (the Nether roof). The HUD hint
	 * shows "protected" for these.
	 */
	public static boolean isProtected(Level level, BlockPos pos, BlockState state) {
		if (!state.is(AbsorbTags.PROTECTED_BLOCKS)) return false;
		DimensionType type = level.dimensionType();
		if (pos.getY() < type.minY() + AbsorbCaps.PROTECTED_LAYERS) return true;
		return type.hasCeiling() && pos.getY() >= type.minY() + type.logicalHeight() - AbsorbCaps.PROTECTED_LAYERS;
	}
}
