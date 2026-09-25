package dev.absorbaholic.absorb;

import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelStacking;
import dev.absorbaholic.registry.AbsorbTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

/**
 * The absorb rules that do not depend on server-only state, shared by the server validation / use suppression, the
 * client's use-key intercept and the HUD hint (common code, no client classes): pose and hands, world protection,
 * whether a target is worth intercepting at all (trait not maxed, mob weak enough), vanilla block-action
 * restrictions, and what a target carries that absorbing would destroy. The world mode, target source, cooldown and
 * server-only permission checks (spawn protection, world border, claim mods) are separate.
 */
public final class AbsorbRules {
	private AbsorbRules() {}

	/**
	 * Sneaking, BOTH hands empty (an off-hand shield, torch or food keeps its vanilla use), not creative / spectator,
	 * alive. Uses the player's own view of its game mode.
	 */
	public static boolean poseAllows(Player player) {
		return player.isShiftKeyDown()
				&& player.getMainHandItem().isEmpty()
				&& player.getOffhandItem().isEmpty()
				&& !player.isSpectator()
				&& !player.isCreative()
				&& player.isAlive();
	}

	/**
	 * True while another absorption of a source can still raise its trait. A target whose source is maxed is neither
	 * intercepted nor suppressed (vanilla sneak-use on trapdoors, furnaces … keeps working); the server still refuses
	 * an explicit channel on it.
	 */
	public static boolean canGrow(int traitLevel, int maxLevel) {
		return LevelStacking.canAbsorb(traitLevel, maxLevel);
	}

	/** A mob can be absorbed only at or below {@link AbsorbCaps#MOB_HEALTH_THRESHOLD} of its max health. */
	public static boolean weakEnough(LivingEntity entity) {
		return entity.getHealth() <= AbsorbCaps.MOB_HEALTH_THRESHOLD * entity.getMaxHealth();
	}

	/**
	 * Vanilla's block-action restriction (adventure mode without a matching {@code can_break} tool, spectators): true
	 * if {@code player} may not break the block at {@code pos} in game mode {@code mode}. Entities stay absorbable.
	 */
	public static boolean blockActionRestricted(Player player, Level level, BlockPos pos, GameType mode) {
		return player.blockActionRestricted(level, pos, mode);
	}

	/**
	 * World protection (lead decision): a block in {@code #absorbaholic:bedrock_protected} (bedrock) cannot be absorbed
	 * in the bottom {@link AbsorbCaps#PROTECTED_LAYERS} layers of the dimension, at / above
	 * {@code minY + logicalHeight - PROTECTED_LAYERS} in a dimension with a ceiling (the Nether roof), nor anywhere in
	 * the End (exit podium and gateway shells). The HUD hint shows "protected" for these.
	 */
	public static boolean isProtected(Level level, BlockPos pos, BlockState state) {
		if (!state.is(AbsorbTags.PROTECTED_BLOCKS)) return false;
		if (level.dimension() == Level.END) return true;
		DimensionType type = level.dimensionType();
		if (pos.getY() < type.minY() + AbsorbCaps.PROTECTED_LAYERS) return true;
		return type.hasCeiling() && pos.getY() >= type.minY() + type.logicalHeight() - AbsorbCaps.PROTECTED_LAYERS;
	}

	/** What absorbing a block would silently destroy (D8: refuse instead). */
	public enum Contents {
		NONE,
		/** A non-empty container, a lectern with a book, a campfire with items. */
		ITEMS,
		/** A beehive / bee nest with bees inside. */
		BEES
	}

	/** Contents of {@code blockEntity} that a silent removal would destroy. */
	public static Contents contents(@Nullable BlockEntity blockEntity) {
		if (blockEntity instanceof Container container) return container.isEmpty() ? Contents.NONE : Contents.ITEMS;
		if (blockEntity instanceof LecternBlockEntity lectern) return lectern.hasBook() ? Contents.ITEMS : Contents.NONE;
		if (blockEntity instanceof CampfireBlockEntity campfire) {
			return campfire.getItems().stream().anyMatch(s -> !s.isEmpty()) ? Contents.ITEMS : Contents.NONE;
		}
		if (blockEntity instanceof BeehiveBlockEntity hive) return hive.isEmpty() ? Contents.NONE : Contents.BEES;
		return Contents.NONE;
	}

	/**
	 * True if the mob carries anything a discard would destroy: any equipment slot (armor, held items, saddle, body
	 * armor, llama carpet), a chest (donkeys, mules, llamas) or an inventory (allays, villagers, piglins …).
	 */
	public static boolean carriesItems(LivingEntity entity) {
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			if (!entity.getItemBySlot(slot).isEmpty()) return true;
		}
		if (entity instanceof AbstractChestedHorse chested && chested.hasChest()) return true;
		return entity instanceof InventoryCarrier carrier && !carrier.getInventory().isEmpty();
	}
}
