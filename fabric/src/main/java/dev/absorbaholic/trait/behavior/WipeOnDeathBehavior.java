package dev.absorbaholic.trait.behavior;

import com.mojang.serialization.MapCodec;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import net.minecraft.util.Unit;

/**
 * {@code absorbaholic:wipe_on_death} (dragon egg weakness): any death while this entry is active wipes all of the
 * player's traits, regardless of the world's keep-on-death. No params. Evaluated by PlayerLifecycle through
 * {@code TraitEngine.wipesOnDeath}.
 */
public final class WipeOnDeathBehavior implements Behavior<Unit> {
	public static final BehaviorType<Unit> TYPE =
			BehaviorRegistry.register("wipe_on_death", MapCodec.unit(Unit.INSTANCE), new WipeOnDeathBehavior());

	@Override
	public boolean wipesOnDeath(ActiveBehavior<Unit> self) {
		return true;
	}
}
