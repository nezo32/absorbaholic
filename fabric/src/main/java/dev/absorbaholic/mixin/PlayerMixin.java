package dev.absorbaholic.mixin;

import dev.absorbaholic.trait.MovementFlagsHolder;
import dev.absorbaholic.trait.MovementState;
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
 * </ul>
 * Gliding is NOT a mixin: {@code EntityElytraEvents.CUSTOM} (registered by TraitEngine in common init, both sides)
 * returns true for {@code MovementFlags.GLIDE}; a canGlide mixin would crash vanilla's elytra-damage branch.
 */
@Mixin(Player.class)
public abstract class PlayerMixin implements MovementFlagsHolder {
	@Unique
	private MovementState absorbaholic$movement = MovementState.NONE;

	@Override
	public MovementState absorbaholic$movement() {
		return absorbaholic$movement;
	}

	@Override
	public void absorbaholic$setMovement(MovementState state) {
		absorbaholic$movement = state == null ? MovementState.NONE : state;
	}
}
