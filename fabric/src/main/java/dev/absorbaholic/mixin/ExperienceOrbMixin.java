package dev.absorbaholic.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@code playerTouch(Lnet/minecraft/world/entity/player/Player;)V}: {@code @ModifyArg} on the
 * {@code Player.giveExperiencePoints(I)V} invoke → {@code TraitEngine.modifyExperience} for a ServerPlayer (orb XP
 * only, after Mending took its share; commands and enchanting are untouched).
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
	@ModifyArg(method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;giveExperiencePoints(I)V"))
	private int absorbaholic$modifyExperience(int amount, @Local(argsOnly = true) Player player) {
		return player instanceof ServerPlayer serverPlayer ? TraitEngine.modifyExperience(serverPlayer, amount) : amount;
	}
}
