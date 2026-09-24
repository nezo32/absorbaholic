package dev.absorbaholic.mixin;

import dev.absorbaholic.trait.MovementFlagsHolder;
import dev.absorbaholic.trait.MovementState;
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player-level trait hooks (both sides; server logic only acts on ServerPlayer). Implements the
 * {@link MovementFlagsHolder} duck, plus:
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

	@ModifyVariable(method = "causeFoodExhaustion(F)V", at = @At("HEAD"), argsOnly = true)
	private float absorbaholic$modifyExhaustion(float amount) {
		return (Object) this instanceof ServerPlayer player ? TraitEngine.modifyExhaustion(player, amount) : amount;
	}

	@Inject(method = "causeFallDamage(DFLnet/minecraft/world/damagesource/DamageSource;)Z", at = @At("HEAD"))
	private void absorbaholic$land(double fallDistance, float damageModifier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer player) TraitEngine.onLand(player, fallDistance);
	}
}
