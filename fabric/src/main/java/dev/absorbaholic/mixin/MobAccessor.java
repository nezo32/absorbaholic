package dev.absorbaholic.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Goal / target selectors of a mob (no public getter in 26.2) for mob-relation behaviors that add goals. */
@Mixin(Mob.class)
public interface MobAccessor {
	@Accessor("goalSelector")
	GoalSelector absorbaholic$goalSelector();

	@Accessor("targetSelector")
	GoalSelector absorbaholic$targetSelector();
}
