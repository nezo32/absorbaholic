package dev.absorbaholic.mixin;

import dev.absorbaholic.trait.MovementFlagsHolder;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Player-level trait hooks (both sides; server logic only acts on ServerPlayer). Implements the
 * {@link MovementFlagsHolder} duck now (skeleton); WP-ENGINE adds the injections:
 * <ul>
 * <li>{@code causeFoodExhaustion(F)V} HEAD {@code @ModifyVariable(argsOnly)} → {@code TraitEngine.modifyExhaustion};</li>
 * <li>{@code causeFallDamage(DFLnet/minecraft/world/damagesource/DamageSource;)Z} HEAD → {@code TraitEngine.onLand}
 *     for a ServerPlayer.</li>
 * <li>{@code giveExperiencePoints(I)V} HEAD {@code @ModifyVariable(argsOnly)}, positive amounts →
 *     {@code TraitEngine.modifyExperience}.</li>
 * </ul>
 * Gliding is NOT a mixin: {@code EntityElytraEvents.CUSTOM} (registered by TraitEngine in common init, both sides)
 * returns true for {@code MovementFlags.GLIDE}; a canGlide mixin would crash vanilla's elytra-damage branch.
 */
@Mixin(Player.class)
public abstract class PlayerMixin implements MovementFlagsHolder {
	@Unique
	private int absorbaholic$movementFlags;

	@Override
	public int absorbaholic$movementFlags() {
		return absorbaholic$movementFlags;
	}

	@Override
	public void absorbaholic$setMovementFlags(int flags) {
		absorbaholic$movementFlags = flags;
	}
}
