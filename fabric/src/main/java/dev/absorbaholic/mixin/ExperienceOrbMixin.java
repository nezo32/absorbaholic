package dev.absorbaholic.mixin;

import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-ENGINE. {@code playerTouch(Lnet/minecraft/world/entity/player/Player;)V}: {@code @ModifyArg} on the {@code Player.giveExperiencePoints(I)V} invoke → {@code TraitEngine.modifyExperience} for a ServerPlayer (orb XP only; commands and enchanting untouched).
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
}
