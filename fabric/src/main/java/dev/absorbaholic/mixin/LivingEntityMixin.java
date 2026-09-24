package dev.absorbaholic.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.trait.MovementFlags;
import dev.absorbaholic.trait.MovementFlagsHolder;
import dev.absorbaholic.trait.MovementState;
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LivingEntity-level trait hooks (each checks {@code this instanceof ServerPlayer} / {@code Player} first):
 * <ul>
 * <li>{@code hurtServer} HEAD {@code @ModifyVariable(argsOnly, float)}: attacker ({@code source.getEntity()})
 *     ServerPlayer → {@code TraitEngine.modifyOutgoingDamage}, then victim ServerPlayer →
 *     {@code TraitEngine.modifyIncomingDamage} (trait floor + weakness extra through the gate).</li>
 * <li>{@code heal(F)V} HEAD → {@code TraitEngine.modifyHeal(p, amount, natural)}.</li>
 * <li>{@code addEffect(MobEffectInstance, Entity)} HEAD → {@code TraitEngine.modifyEffect} (deny is ALLOW_ADD).</li>
 * <li>{@code canStandOnFluid} HEAD → WALK_ON_WATER / WALK_ON_LAVA (not while sneaking). Both sides.</li>
 * <li>{@code onClimbable} HEAD → CLIMB_WALLS while pushing against a wall; the climb speed replaces the vanilla
 *     ladder speed (0.2) away from real climbable blocks. Both sides.</li>
 * <li>{@code travelInWater} TAIL → SINK_IN_WATER on the client (the server behavior moves vanilla clients):
 *     {@code vy = max(vy - sinkSpeed, SINK_MAX_FALL_VELOCITY)}, no swimming up (except climbing out at a shore).</li>
 * <li>{@code getVisibilityPercent} by NAME ONLY (26.2: (Entity), 26.3: (ServerLevel, Entity)), RETURN →
 *     {@code TraitEngine.visibilityFactor}.</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@ModifyVariable(method = "hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z",
			at = @At("HEAD"), argsOnly = true)
	private float absorbaholic$modifyDamage(float amount, @Local(argsOnly = true) DamageSource source) {
		LivingEntity self = (LivingEntity) (Object) this;
		float result = amount;
		if (source.getEntity() instanceof ServerPlayer attacker && attacker != self) {
			result = TraitEngine.modifyOutgoingDamage(attacker, self, source, result);
		}
		if (self instanceof ServerPlayer player) result = TraitEngine.modifyIncomingDamage(player, source, result);
		return result;
	}

	@ModifyVariable(method = "heal(F)V", at = @At("HEAD"), argsOnly = true)
	private float absorbaholic$modifyHeal(float amount) {
		return (Object) this instanceof ServerPlayer player ? TraitEngine.modifyHeal(player, amount, TraitEngine.isNaturalRegen()) : amount;
	}

	@ModifyVariable(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
			at = @At("HEAD"), argsOnly = true)
	private MobEffectInstance absorbaholic$modifyEffect(MobEffectInstance effect) {
		return (Object) this instanceof ServerPlayer player ? TraitEngine.modifyEffect(player, effect) : effect;
	}

	@Inject(method = "canStandOnFluid(Lnet/minecraft/world/level/material/FluidState;)Z", at = @At("HEAD"), cancellable = true)
	private void absorbaholic$standOnFluid(FluidState fluid, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof Player player) || player.isShiftKeyDown()) return;
		MovementState m = ((MovementFlagsHolder) player).absorbaholic$movement();
		if (m.has(MovementFlags.WALK_ON_WATER) && fluid.is(FluidTags.WATER) || m.has(MovementFlags.WALK_ON_LAVA) && fluid.is(FluidTags.LAVA)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "onClimbable()Z", at = @At("HEAD"), cancellable = true)
	private void absorbaholic$climbWalls(CallbackInfoReturnable<Boolean> cir) {
		if (absorbaholic$wallClimbing()) cir.setReturnValue(true);
	}

	/** Vanilla climbs at 0.2 blocks/tick; a wall climber away from ladders / vines climbs at its trait speed. */
	@ModifyConstant(method = "handleRelativeFrictionAndCalculateMovement", constant = @Constant(doubleValue = 0.2))
	private double absorbaholic$climbSpeed(double speed) {
		if (!absorbaholic$wallClimbing()) return speed;
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.getInBlockState().is(BlockTags.CLIMBABLE)) return speed;
		float climb = ((MovementFlagsHolder) self).absorbaholic$movement().climbSpeed();
		return climb > 0.0F ? climb : speed;
	}

	@Inject(method = "travelInWater", at = @At("TAIL"))
	private void absorbaholic$sinkInWater(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!self.level().isClientSide() || !(self instanceof Player player)) return;
		MovementState m = ((MovementFlagsHolder) player).absorbaholic$movement();
		if (!m.has(MovementFlags.SINK_IN_WATER) || m.sinkSpeed() <= 0.0F || player.isPassenger() || player.getAbilities().flying
				|| player.getInBlockState().is(Blocks.BUBBLE_COLUMN)) {
			return;
		}
		Vec3 v = player.getDeltaMovement();
		double vy = player.horizontalCollision ? v.y : Math.min(v.y, 0.0); // no swimming up, except out at a shore
		if (vy > AbsorbCaps.SINK_MAX_FALL_VELOCITY) vy = Math.max(vy - m.sinkSpeed(), AbsorbCaps.SINK_MAX_FALL_VELOCITY);
		player.setDeltaMovement(v.x, vy, v.z);
	}

	@Inject(method = "getVisibilityPercent", at = @At("RETURN"), cancellable = true)
	private void absorbaholic$visibility(CallbackInfoReturnable<Double> cir, @Local(argsOnly = true) @Nullable Entity looker) {
		if ((Object) this instanceof ServerPlayer player) {
			double factor = TraitEngine.visibilityFactor(player, looker);
			if (factor != 1.0) cir.setReturnValue(cir.getReturnValueD() * factor);
		}
	}

	@Unique
	private boolean absorbaholic$wallClimbing() {
		return (Object) this instanceof Player player && player.horizontalCollision
				&& ((MovementFlagsHolder) player).absorbaholic$movement().has(MovementFlags.CLIMB_WALLS);
	}
}
