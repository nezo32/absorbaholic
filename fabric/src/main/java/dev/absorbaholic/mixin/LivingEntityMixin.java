package dev.absorbaholic.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.trait.MovementFlags;
import dev.absorbaholic.trait.MovementFlagsHolder;
import dev.absorbaholic.trait.MovementState;
import dev.absorbaholic.trait.TraitEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
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
 * <li>{@code getLiquidCollisionShape} HEAD → a full-block surface for such a player, so a fluid source's top (8/9 high)
 *     is solid ground with the feet above the fluid (vanilla returns an empty shape for everything but the Strider).
 *     Both sides: movement is client authoritative and reads the synced flags.</li>
 * <li>{@code travel} HEAD → such a player (not sneaking) inside the fluid it walks on rises at least
 *     {@code AbsorbCaps.FLUID_WALK_RISE_SPEED} until it stands on the surface (canStandOnFluid gives it air physics
 *     there, so without this it would sink), but only below a standable surface ({@link #absorbaholic$surfaceAbove}):
 *     never in a falling or flowing column such as a lava fall. Both sides.</li>
 * <li>{@code checkFallDamage} HEAD → landing on the surface of a walked fluid keeps vanilla's fluid landing: water
 *     cancels the fall, lava scales it by {@code AbsorbCaps.FLUID_WALK_LAVA_FALL_FACTOR}.</li>
 * <li>On the server the fluid-walking flags only exist for players whose client has the mod (TraitEngine strips them
 *     otherwise), so a vanilla client that sinks is never corrected back onto the surface.</li>
 * <li>{@code knockback(DDDLDamageSource;FZ)V} HEAD {@code @ModifyVariable(argsOnly, ordinal 0)}: the strength a
 *     ServerPlayer takes → {@code TraitEngine.modifyKnockback} (knockback_multiplier).</li>
 * <li>{@code readAdditionalSaveData} RETURN → the raw saved {@code Health} of a ServerPlayer, before vanilla clamped it
 *     to the max without our modifiers → {@code TraitEngine.onHealthLoaded}.</li>
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

	@Inject(method = "getLiquidCollisionShape()Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true)
	private void absorbaholic$fluidSurface(CallbackInfoReturnable<VoxelShape> cir) {
		// the fluid type is checked by LiquidBlock through canStandOnFluid (hooked above)
		if (absorbaholic$fluidFlags() != 0) cir.setReturnValue(Shapes.block());
	}

	@Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
	private void absorbaholic$riseInWalkedFluid(Vec3 input, CallbackInfo ci) {
		int flags = absorbaholic$fluidFlags();
		if (flags == 0) return;
		Player player = (Player) (Object) this;
		boolean lava = (flags & MovementFlags.WALK_ON_LAVA) != 0 && player.isInLava();
		boolean water = (flags & MovementFlags.WALK_ON_WATER) != 0 && player.isInWater();
		if ((lava || water) && absorbaholic$surfaceAbove(player, lava ? FluidTags.LAVA : FluidTags.WATER)) {
			Vec3 v = player.getDeltaMovement();
			if (v.y < AbsorbCaps.FLUID_WALK_RISE_SPEED) player.setDeltaMovement(v.x, AbsorbCaps.FLUID_WALK_RISE_SPEED, v.z);
		}
	}

	@Inject(method = "checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"))
	private void absorbaholic$fluidLanding(double ya, boolean onGround, BlockState onState, BlockPos pos, CallbackInfo ci) {
		if (!onGround) return;
		int flags = absorbaholic$fluidFlags();
		if (flags == 0) return;
		LivingEntity self = (LivingEntity) (Object) this;
		FluidState fluid = onState.getFluidState();
		if (!fluid.isSource()) return;
		double factor;
		if ((flags & MovementFlags.WALK_ON_WATER) != 0 && fluid.is(FluidTags.WATER)) {
			factor = 0.0;
		} else if ((flags & MovementFlags.WALK_ON_LAVA) != 0 && fluid.is(FluidTags.LAVA)) {
			factor = AbsorbCaps.FLUID_WALK_LAVA_FALL_FACTOR;
		} else {
			return;
		}
		// Entity#checkFallDamage first adds this tick's drop (-ya, unless in water), then lands with the total
		double added = !self.isInWater() && ya < 0.0 ? -ya : 0.0;
		double total = self.fallDistance + added;
		if (total > 0.0) self.fallDistance = total * factor - added;
	}

	@ModifyVariable(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private double absorbaholic$modifyKnockback(double power, @Local(argsOnly = true) @Nullable DamageSource source) {
		return (Object) this instanceof ServerPlayer player ? TraitEngine.modifyKnockback(player, source, power) : power;
	}

	@Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
	private void absorbaholic$savedHealth(ValueInput input, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer player) TraitEngine.onHealthLoaded(player, input.getFloatOr("Health", Float.NaN));
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

	/** WALK_ON_WATER / WALK_ON_LAVA bits of a player that is not sneaking, riding or flying; 0 otherwise. */
	@Unique
	private int absorbaholic$fluidFlags() {
		if (!((Object) this instanceof Player player) || player.isShiftKeyDown() || player.isPassenger() || player.getAbilities().flying) return 0;
		return ((MovementFlagsHolder) player).absorbaholic$movement().flags() & (MovementFlags.WALK_ON_WATER | MovementFlags.WALK_ON_LAVA);
	}

	/**
	 * True when a standable surface of {@code fluid} is above the player's feet: the feet block and every block up to
	 * the surface are source blocks of that fluid, and the block above the top one holds none of it (the condition under
	 * which LiquidBlock gives a fluid walker a collision top). Scans at most {@code AbsorbCaps.FLUID_WALK_SURFACE_SCAN}.
	 */
	@Unique
	private static boolean absorbaholic$surfaceAbove(Player player, TagKey<Fluid> fluid) {
		Level level = player.level();
		BlockPos.MutableBlockPos pos = BlockPos.containing(player.getX(), player.getY(), player.getZ()).mutable();
		for (int i = 0; i < AbsorbCaps.FLUID_WALK_SURFACE_SCAN; i++) {
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof LiquidBlock) || !state.getFluidState().isSource() || !state.getFluidState().is(fluid)) return false;
			pos.move(0, 1, 0);
			if (!level.getFluidState(pos).is(fluid)) return true;
		}
		return false;
	}

	@Unique
	private boolean absorbaholic$wallClimbing() {
		return (Object) this instanceof Player player && player.horizontalCollision
				&& ((MovementFlagsHolder) player).absorbaholic$movement().has(MovementFlags.CLIMB_WALLS);
	}
}
