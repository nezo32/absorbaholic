package dev.absorbaholic.trait.behavior;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * {@code absorbaholic:item_magnet}: every 5 ticks while the condition holds, item entities within {@code radius}
 * (max 10) that the player could pick up (no pickup delay, not thrown by this player in the last 40 ticks, room in the
 * inventory) drift toward the player at 0.25 blocks per tick. Nothing is teleported, vanilla pickup still applies and
 * experience orbs are not affected. A radius &lt;= 0 disables the entry at that level.
 *
 * <pre>{"type": "absorbaholic:item_magnet", "radius": [4, 6, 8]}</pre>
 */
public final class ItemMagnetBehavior implements Behavior<ItemMagnetBehavior.Params> {
	static final int INTERVAL = 5;
	/** Items the player threw are left alone for this long (so dropping an item works). */
	static final int OWN_THROW_TICKS = 40;
	/** At most this many items are pulled per pulse. */
	static final int MAX_ITEMS = 64;

	public record Params(LevelValue radius, Condition condition) {
		public static final MapCodec<Params> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				LevelValue.CODEC.fieldOf("radius").forGetter(Params::radius),
				Condition.FIELDS.forGetter(Params::condition)
		).apply(i, Params::new));
	}

	public static final BehaviorType<Params> TYPE = BehaviorRegistry.register("item_magnet", Params.CODEC, new ItemMagnetBehavior());

	@Override
	public int tickInterval(Params p) {
		return INTERVAL;
	}

	@Override
	public void tick(ActiveBehavior<Params> self, ServerPlayer player) {
		double radius = Math.min(self.params().radius().at(self.level()), AbsorbCaps.ITEM_MAGNET_MAX_RADIUS);
		if (!(radius > 0.0) || player.isSpectator() || !EntryStates.condition(player, self, self.params().condition())) return;
		Vec3 center = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
		double maxSq = radius * radius;
		int n = 0;
		for (ItemEntity item : player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(radius),
				e -> e.isAlive() && e.distanceToSqr(center) <= maxSq && canPickUp(e, player))) {
			if (++n > MAX_ITEMS) break;
			Vec3 delta = center.subtract(item.position());
			double distance = delta.length();
			if (distance < 1.0E-3) continue;
			item.setDeltaMovement(delta.scale(Math.min(AbsorbCaps.ITEM_MAGNET_PULL, distance) / distance));
			item.needsSync = true;
		}
	}

	private static boolean canPickUp(ItemEntity item, ServerPlayer player) {
		if (item.hasPickUpDelay()) return false;
		if (item.getOwner() == player && item.getAge() < OWN_THROW_TICKS) return false;
		ItemStack stack = item.getItem();
		Inventory inventory = player.getInventory();
		return !stack.isEmpty() && (inventory.getFreeSlot() >= 0 || inventory.getSlotWithRemainingSpace(stack) >= 0);
	}
}
