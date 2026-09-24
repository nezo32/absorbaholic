package dev.absorbaholic.test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

import com.mojang.serialization.MapCodec;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerRuntime;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.registry.SourceTargets;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.AttributeEntry;
import dev.absorbaholic.trait.Behavior;
import dev.absorbaholic.trait.BehaviorEntry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Condition;
import dev.absorbaholic.trait.Hook;
import dev.absorbaholic.trait.MovementFlags;
import dev.absorbaholic.trait.MovementFlagsHolder;
import dev.absorbaholic.trait.MovementState;
import dev.absorbaholic.trait.TargetFilter;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.trait.WeaknessDamage;
import dev.absorbaholic.trait.behavior.DamageMultiplierBehavior;
import dev.absorbaholic.trait.behavior.WipeOnDeathBehavior;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * WP-ENGINE server gametests: attributes apply / remove with mode, game mode and removal; clamps on final values;
 * the weakness damage gate (direct and multiplier extra) and trait floor; immunity never for /kill, void or generic;
 * every hook of ARCHITECTURE §4.3 reached through its real wiring (events, mixins, packet handlers); ability triggers
 * from input edges and the swing packet; one trigger → one ability; broken behaviors never escape.
 *
 * <p>Test sources are built in code with no targets and exist in {@link SourceRegistry} only inside a synchronous
 * block (the registry is shared by all parallel tests). Tests that need server ticks re-apply their traits every tick
 * ({@link #keep}) because other tests may mark every player dirty meanwhile (mode toggles). Probe behaviors are test
 * only types that are never registered.
 */
public class TraitEngineGameTests {
	private static final double EPS = 1.0E-6;

	// ---- attributes ------------------------------------------------------------------------------------------

	@GameTest
	public void attributesFollowModeGameModeAndRemoval(GameTestHelper h) {
		ServerPlayer p = player(h);
		SourceDefinition src = source("attr_basic", 0x3366FF,
				List.of(attr(Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, 0.5)), List.of(),
				List.of(attr(Attributes.MAX_HEALTH, AttributeModifier.Operation.ADD_VALUE, -4.0)), List.of());
		try {
			give(p, 1, 1, src);
			near(h, p.getAttributeValue(Attributes.MOVEMENT_SPEED), 0.15, 1.0E-6, "trait speed ×1.5");
			h.assertValueEqual(p.getMaxHealth(), 16.0F, "weakness max health -4");
			h.assertTrue(p.getHealth() <= 16.0F, "health clamped to the new max health");

			withSources(() -> {
				TestSupport.setMode(h, false);
				try {
					TraitEngine.recompute(p);
					h.assertFalse(TraitEngine.isActive(p), "mode OFF → dormant");
					assertNoModifiers(h, p, "mode OFF");
					h.assertTrue(TraitEngine.active(p).isEmpty(), "mode OFF → empty active set");
				} finally {
					TestSupport.setMode(h, true);
				}
				TraitEngine.recompute(p);
				near(h, p.getAttributeValue(Attributes.MOVEMENT_SPEED), 0.15, 1.0E-6, "mode ON again → speed back");

				for (GameType dormant : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
					p.setGameMode(dormant);
					TraitEngine.recompute(p);
					h.assertFalse(TraitEngine.isActive(p), dormant + " is dormant");
					assertNoModifiers(h, p, dormant.getName());
				}
				p.setGameMode(GameType.ADVENTURE);
				TraitEngine.recompute(p);
				h.assertTrue(TraitEngine.isActive(p), "adventure is active");
				h.assertValueEqual(p.getMaxHealth(), 16.0F, "adventure → weakness applies");
				p.setGameMode(GameType.SURVIVAL);
			}, src);

			PlayerData.setTraits(p, PlayerTraits.EMPTY);
			TraitEngine.recompute(p);
			assertNoModifiers(h, p, "traits removed");
			h.assertTrue(PlayerData.runtime(p).active.isEmpty(), "traits removed → empty set");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void modeChangeMarksPlayersDirty(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			PlayerRuntime rt = PlayerData.runtime(p);
			TraitEngine.recompute(p);
			h.assertFalse(rt.dirty, "recompute clears dirty");
			TestSupport.setMode(h, false);
			boolean dirtyAfterOff = rt.dirty;
			TestSupport.setMode(h, true);
			h.assertTrue(dirtyAfterOff, "mode OFF marks active players dirty");
			TraitEngine.recompute(p);
			PlayerData.setTraits(p, new PlayerTraits(List.of(new TraitEntry(Identifier.fromNamespaceAndPath("absorbaholic_test", "none"), 1, 1, false, false))));
			h.assertTrue(rt.dirty, "a traits change marks dirty");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void clampsHoldOnFinalValues(GameTestHelper h) {
		record Case(Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, AttributeModifier.Operation op, double amount, double expected) {}
		List<Case> cases = List.of(
				new Case(Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, 5.0, 0.2),
				new Case(Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, -0.9, 0.04),
				new Case(Attributes.SCALE, AttributeModifier.Operation.ADD_VALUE, 5.0, 2.0),
				new Case(Attributes.SCALE, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, -0.8, 0.5),
				new Case(Attributes.MAX_HEALTH, AttributeModifier.Operation.ADD_VALUE, 100.0, 60.0),
				new Case(Attributes.MAX_HEALTH, AttributeModifier.Operation.ADD_VALUE, -100.0, 6.0),
				new Case(Attributes.BLOCK_INTERACTION_RANGE, AttributeModifier.Operation.ADD_VALUE, 10.0, 8.0),
				new Case(Attributes.ENTITY_INTERACTION_RANGE, AttributeModifier.Operation.ADD_VALUE, -5.0, 2.0),
				new Case(Attributes.JUMP_STRENGTH, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, -1.0, 0.2),
				new Case(Attributes.GRAVITY, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 2.0, 0.16),
				new Case(Attributes.STEP_HEIGHT, AttributeModifier.Operation.ADD_VALUE, -0.5, 0.6),
				new Case(Attributes.ARMOR, AttributeModifier.Operation.ADD_VALUE, 50.0, 30.0),
				new Case(Attributes.ATTACK_DAMAGE, AttributeModifier.Operation.ADD_VALUE, 50.0, 21.0));
		for (int i = 0; i < cases.size(); i++) {
			Case c = cases.get(i);
			ServerPlayer p = player(h);
			try {
				give(p, 1, 0, source("clamp_" + i, 0xFFFFFF, List.of(attr(c.attribute(), c.op(), c.amount())), List.of(), List.of(), List.of()));
				near(h, p.getAttributeValue(c.attribute()), c.expected(), 1.0E-6, "clamped " + c.attribute().getRegisteredName() + " " + c.amount());
				if (c.attribute() == Attributes.MAX_HEALTH) h.assertTrue(p.getHealth() <= p.getMaxHealth(), "health <= clamped max health");
			} finally {
				remove(p);
			}
		}

		// an in-range value is never touched and stacking levels still respects the cap
		ServerPlayer p = player(h);
		try {
			SourceDefinition s = source("clamp_in_range", 0xFFFFFF,
					List.of(attr(Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, 0.25)), List.of(), List.of(), List.of());
			give(p, 1, 0, s);
			near(h, p.getAttributeValue(Attributes.MOVEMENT_SPEED), 0.125, 1.0E-6, "in range → no clamp");
			h.assertTrue(p.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(AbsorbCapsIds.CLAMP) == null, "no clamp modifier when in range");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void reclampFollowsOtherModifiers(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			give(p, 1, 0, source("reclamp", 0xFFFFFF,
					List.of(attr(Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, 0.9)), List.of(), List.of(), List.of()));
			near(h, p.getAttributeValue(Attributes.MOVEMENT_SPEED), 0.19, 1.0E-6, "×1.9 is in range");
			// another source (sprinting, an effect, another mod) pushes it past ×2
			Identifier other = Identifier.fromNamespaceAndPath("absorbaholic_test", "other");
			p.getAttribute(Attributes.MOVEMENT_SPEED).addOrUpdateTransientModifier(new AttributeModifier(other, 0.3, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			dev.absorbaholic.trait.AttributeApplier.reclamp(p);
			near(h, p.getAttributeValue(Attributes.MOVEMENT_SPEED), 0.2, 1.0E-6, "reclamp keeps the final value at ×2");
			// the other modifier alone exceeding the cap is never pushed back below it by us
			p.getAttribute(Attributes.MOVEMENT_SPEED).addOrUpdateTransientModifier(new AttributeModifier(other, 2.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			dev.absorbaholic.trait.AttributeApplier.reclamp(p);
			near(h, p.getAttributeValue(Attributes.MOVEMENT_SPEED), 0.3, 1.0E-6, "without ours already ×3: ours may not add more");
			p.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(other);
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- damage ----------------------------------------------------------------------------------------------

	@GameTest(maxTicks = 60)
	public void directWeaknessDamageIsGated(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			TraitEngine.recompute(p);
			float dealt = WeaknessDamage.hurt(p, 30.0F);
			h.assertValueEqual(dealt, AbsorbCaps.WEAKNESS_DAMAGE_BUDGET, "one window allows 8 HP");
			h.assertValueEqual(p.getHealth(), 12.0F, "20 - 8");
			h.assertValueEqual(WeaknessDamage.hurt(p, 5.0F), 0.0F, "budget spent in this window");
			h.assertValueEqual(p.getHealth(), 12.0F, "no damage over budget");
			h.assertTrue(p.getLastDamageSource() != null && p.getLastDamageSource().is(WeaknessDamage.TYPE), "absorbaholic:weakness damage type");
			DamageSource s = WeaknessDamage.source(p, null);
			h.assertTrue(s.is(DamageTypeTags.BYPASSES_ARMOR) && s.is(DamageTypeTags.BYPASSES_SHIELD) && s.is(DamageTypeTags.NO_KNOCKBACK),
					"weakness damage bypasses armor and shields, no knockback");
			h.assertFalse(s.scalesWithDifficulty(), "scaling never");
		} finally {
			remove(p);
		}

		// a single hit never takes a full-health player below 1 HP (max health 6 < budget 8)
		ServerPlayer small = player(h);
		try {
			give(small, 0, 1, source("gate_small", 0xFFFFFF, List.of(), List.of(),
					List.of(attr(Attributes.MAX_HEALTH, AttributeModifier.Operation.ADD_VALUE, -14.0)), List.of()));
			small.setHealth(small.getMaxHealth());
			h.assertValueEqual(small.getMaxHealth(), 6.0F, "max health 6");
			h.assertValueEqual(WeaknessDamage.hurt(small, 8.0F), 5.0F, "full health: at most health - 1");
			h.assertValueEqual(small.getHealth(), 1.0F, "left at 1 HP");
			h.assertTrue(small.isAlive(), "alive");
		} finally {
			remove(small);
		}

		// the window rolls: after 20 ticks the budget is back
		ServerPlayer later = player(h);
		TraitEngine.recompute(later);
		WeaknessDamage.hurt(later, 8.0F);
		h.runAfterDelay(AbsorbCaps.WEAKNESS_DAMAGE_WINDOW_TICKS + 1, () -> {
			later.setHealth(later.getMaxHealth() - 1.0F);
			h.assertValueEqual(WeaknessDamage.hurt(later, 3.0F), 3.0F, "new window, new budget");
			remove(later);
			h.succeed();
		});
	}

	@GameTest
	public void weaknessMultiplierExtraIsGated(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			give(p, 0, 1, source("gate_multiplier", 0xFFFFFF, List.of(), List.of(), List.of(), List.of(damageMultiplier(3.0))));
			// real damage pipeline (LivingEntityMixin): base 4, weakness ×3 → extra 8, all within budget
			p.hurtServer(level(h), p.damageSources().generic(), 4.0F);
			h.assertValueEqual(p.getHealth(), 8.0F, "4 base + 8 gated extra");
			// same window: only the base remains
			float again = TraitEngine.modifyIncomingDamage(p, p.damageSources().magic(), 2.0F);
			h.assertValueEqual(again, 2.0F, "extra over budget is dropped, base stays");
			// the weakness damage type itself is never multiplied
			h.assertValueEqual(TraitEngine.modifyIncomingDamage(p, WeaknessDamage.source(p, null), 2.0F), 2.0F, "weakness type not multiplied");
		} finally {
			remove(p);
		}

		// full health: base + extra never takes the player below 1 HP
		ServerPlayer full = player(h);
		try {
			give(full, 0, 1, source("gate_multiplier_full", 0xFFFFFF, List.of(), List.of(), List.of(), List.of(damageMultiplier(3.0))));
			float total = TraitEngine.modifyIncomingDamage(full, full.damageSources().generic(), 6.0F);
			h.assertValueEqual(total, 14.0F, "6 base + min(12 extra, 8 budget)");
			ServerPlayer full2 = player(h);
			try {
				give(full2, 0, 1, source("gate_multiplier_full", 0xFFFFFF, List.of(), List.of(), List.of(), List.of(damageMultiplier(3.0))));
				float big = TraitEngine.modifyIncomingDamage(full2, full2.damageSources().generic(), 15.0F);
				h.assertValueEqual(big, 19.0F, "15 base + 4 extra: stops at 1 HP from full");
				float huge = TraitEngine.modifyIncomingDamage(full2, full2.damageSources().generic(), 25.0F);
				h.assertValueEqual(huge, 25.0F, "the base alone may kill (vanilla); no extra");
			} finally {
				remove(full2);
			}
		} finally {
			remove(full);
		}
		h.succeed();
	}

	@GameTest
	public void traitReductionIsFloored(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			give(p, 1, 0, source("floor", 0xFFFFFF, List.of(), List.of(damageMultiplier(0.1), damageMultiplier(0.5)), List.of(), List.of()));
			h.assertValueEqual(TraitEngine.modifyIncomingDamage(p, p.damageSources().generic(), 8.0F), 2.0F, "0.05 floored at 0.25");
			h.assertValueEqual(TraitEngine.modifyIncomingDamage(p, p.damageSources().genericKill(), 8.0F), 8.0F, "/kill untouched");
			h.assertValueEqual(TraitEngine.modifyIncomingDamage(p, p.damageSources().fellOutOfWorld(), 8.0F), 8.0F, "void untouched");
			p.hurtServer(level(h), p.damageSources().generic(), 8.0F);
			h.assertValueEqual(p.getHealth(), 18.0F, "real pipeline: 8 × 0.25");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void immunityNeverForKillVoidOrGeneric(GameTestHelper h) {
		Probe immune = new Probe("immune_all");
		immune.immuneToAll = true;
		List<ResourceKey<DamageType>> never = List.of(DamageTypes.GENERIC_KILL, DamageTypes.FELL_OUT_OF_WORLD, DamageTypes.GENERIC);
		for (ResourceKey<DamageType> type : never) {
			ServerPlayer p = player(h);
			try {
				give(p, 1, 0, source("immune_" + type.identifier().getPath(), 0xFFFFFF, List.of(), List.of(immune.entry()), List.of(), List.of()));
				p.hurtServer(level(h), p.damageSources().source(type), 5.0F);
				h.assertValueEqual(p.getHealth(), 15.0F, type.identifier() + " is never blocked by an immunity");
			} finally {
				remove(p);
			}
		}
		ServerPlayer p = player(h);
		try {
			give(p, 1, 0, source("immune_magic", 0xFFFFFF, List.of(), List.of(immune.entry()), List.of(), List.of()));
			h.assertFalse(p.hurtServer(level(h), p.damageSources().magic(), 5.0F), "immunity cancels the hit");
			h.assertValueEqual(p.getHealth(), 20.0F, "immune to magic");
			h.assertTrue(immune.count(p, Hook.IMMUNITY) > 0, "isImmuneTo hook reached through ALLOW_DAMAGE");
		} finally {
			remove(p);
		}
		// the reference behavior: fire immunity from damage_multiplier 0 with a type filter
		ServerPlayer fire = player(h);
		try {
			BehaviorEntry<DamageMultiplierBehavior.Params> fireImmune = new BehaviorEntry<>(DamageMultiplierBehavior.TYPE,
					new DamageMultiplierBehavior.Params(Optional.of(DamageTypeTags.IS_FIRE), List.of(), TargetFilter.ANY, Condition.ALWAYS,
							new LevelValue.PerLevel(List.of(0.0))));
			give(fire, 1, 0, source("immune_fire", 0xFFFFFF, List.of(), List.of(fireImmune), List.of(), List.of()));
			fire.hurtServer(level(h), fire.damageSources().inFire(), 5.0F);
			h.assertValueEqual(fire.getHealth(), 20.0F, "damage_multiplier 0 on #is_fire = immune");
			fire.hurtServer(level(h), fire.damageSources().genericKill(), 5.0F);
			h.assertValueEqual(fire.getHealth(), 15.0F, "/kill still hurts");
		} finally {
			remove(fire);
		}
		h.succeed();
	}

	@GameTest
	public void dormantPlayersAreUntouched(GameTestHelper h) {
		Probe probe = new Probe("dormant");
		probe.incoming = 3.0F;
		ServerPlayer p = player(h);
		try {
			SourceDefinition s = source("dormant", 0xFFFFFF, List.of(), List.of(), List.of(), List.of(probe.entry(), damageMultiplier(3.0)));
			give(p, 1, 1, s);
			withSources(() -> {
				p.setGameMode(GameType.CREATIVE);
				TraitEngine.recompute(p);
				h.assertValueEqual(WeaknessDamage.hurt(p, 4.0F), 0.0F, "creative: no weakness damage");
				h.assertValueEqual(TraitEngine.modifyIncomingDamage(p, p.damageSources().generic(), 4.0F), 4.0F, "creative: no multiplier");
				h.assertFalse(TraitEngine.fireTrigger(p, Hook.SNEAK_JUMP, null), "creative: no abilities");
				h.assertTrue(probe.count(p, Hook.DEACTIVATE) == 1, "creative deactivates the entry");
				p.setGameMode(GameType.SURVIVAL);
				TraitEngine.recompute(p);
				h.assertTrue(probe.count(p, Hook.ACTIVATE) == 2, "survival activates it again");
			}, s);
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- hooks -----------------------------------------------------------------------------------------------

	@GameTest
	public void combatHooksFire(GameTestHelper h) {
		Probe probe = new Probe("combat");
		probe.outgoing = 2.0F;
		ServerPlayer p = player(h);
		ServerLevel level = level(h);
		try {
			give(p, 1, 0, source("combat", 0xFFFFFF, List.of(), List.of(probe.entry()), List.of(), List.of()));
			h.assertTrue(probe.count(p, Hook.ACTIVATE) == 1, "onActivate on recompute");

			Mob pig = h.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(1, 1, 1));
			float before = pig.getHealth();
			pig.hurtServer(level, p.damageSources().playerAttack(p), 3.0F);
			h.assertTrue(probe.count(p, Hook.OUTGOING_DAMAGE) == 1, "outgoingDamageFactor");
			h.assertTrue(probe.count(p, Hook.DEALT_DAMAGE) == 1, "onDealtDamage (AFTER_DAMAGE, attacker)");
			h.assertValueEqual(before - pig.getHealth(), 6.0F, "dealt ×2 (pigs have no armor)");

			p.hurtServer(level, p.damageSources().mobAttack(pig), 2.0F);
			h.assertTrue(probe.count(p, Hook.INCOMING_DAMAGE) == 1, "incomingDamageFactor");
			h.assertTrue(probe.count(p, Hook.ATTACKED) == 1, "onAttacked (AFTER_DAMAGE, victim)");
			h.assertTrue(probe.count(p, Hook.IMMUNITY) >= 1, "isImmuneTo (ALLOW_DAMAGE)");

			pig.hurtServer(level, p.damageSources().playerAttack(p), 1000.0F);
			h.assertTrue(probe.count(p, Hook.KILL) == 1, "onKill (AFTER_KILLED_OTHER_ENTITY)");

			Mob target = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(1, 1, 1));
			p.setShiftKeyDown(true);
			AttackEntityCallback.EVENT.invoker().interact(p, level, InteractionHand.MAIN_HAND, target, null);
			p.setShiftKeyDown(false);
			AttackEntityCallback.EVENT.invoker().interact(p, level, InteractionHand.MAIN_HAND, target, null);
			h.assertTrue(probe.count(p, Hook.SNEAK_ATTACK) == 1, "onSneakAttack only while sneaking");

			probe.preventTargeting = true;
			target.setTarget(p);
			h.assertTrue(target.getTarget() == null, "preventsTargeting cancels setTarget");
			h.assertTrue(probe.count(p, Hook.PREVENT_TARGETING) >= 1, "preventsTargeting reached");
			target.setLastHurtByMob(p);
			target.setTarget(p);
			h.assertTrue(target.getTarget() == p, "revenge: a mob the player hurt may target it");
			target.discard();
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void foodAndHealHooksFire(GameTestHelper h) {
		Probe probe = new Probe("food");
		probe.heal = 1.5F;
		probe.exhaustion = 2.0F;
		probe.food = new FoodProperties(1, 0.0F, false);
		ServerPlayer p = player(h);
		ServerLevel level = level(h);
		try {
			give(p, 1, 0, source("food", 0xFFFFFF, List.of(), List.of(probe.entry()), List.of(), List.of()));
			p.setHealth(10.0F);
			p.heal(2.0F);
			h.assertValueEqual(p.getHealth(), 13.0F, "healFactor ×1.5");
			h.assertTrue(probe.naturalHeals.equals(List.of(false)), "a potion-style heal is not natural");

			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5.0F);
			for (int i = 0; i < 10; i++) p.getFoodData().tick(p);
			h.assertTrue(probe.naturalHeals.contains(true), "FoodData#tick heal is natural");

			ServerPlayer eater = player(h);
			try {
				give(eater, 1, 0, source("food", 0xFFFFFF, List.of(), List.of(probe.entry()), List.of(), List.of()));
				eater.getFoodData().setFoodLevel(20);
				eater.getFoodData().setSaturation(5.0F);
				eater.causeFoodExhaustion(3.0F); // ×2 = 6 > 4 → one saturation point on the next food tick
				eater.getFoodData().tick(eater);
				h.assertValueEqual(eater.getFoodData().getSaturationLevel(), 4.0F, "exhaustionFactor ×2 reached FoodData");
				h.assertTrue(probe.count(eater, Hook.EXHAUSTION) == 1, "exhaustionFactor");

				eater.getFoodData().setFoodLevel(10);
				ItemStack bread = new ItemStack(Items.BREAD, 2);
				bread.finishUsingItem(level, eater);
				h.assertValueEqual(eater.getFoodData().getFoodLevel(), 11, "modifyFood: bread gives 1");
				h.assertTrue(probe.count(eater, Hook.MODIFY_FOOD) == 1, "modifyFood");
				h.assertTrue(probe.count(eater, Hook.ITEM_CONSUMED) == 1, "onItemConsumed");
				h.assertTrue(probe.consumed != null && probe.consumed.is(Items.BREAD) && probe.consumed.getCount() == 2,
						"onItemConsumed gets a copy taken before consumption");
				h.assertValueEqual(bread.getCount(), 1, "vanilla still consumed one");
			} finally {
				remove(eater);
			}
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void effectHooksAndOwnership(GameTestHelper h) {
		Probe probe = new Probe("effects");
		probe.deny = MobEffects.BLINDNESS;
		probe.amplify = 10;
		ServerPlayer p = player(h);
		Identifier src = Identifier.fromNamespaceAndPath("absorbaholic_test", "effects");
		try {
			give(p, 1, 0, source("effects", 0xFFFFFF, List.of(), List.of(probe.entry()), List.of(), List.of()));
			h.assertFalse(p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 200)), "allowEffect denies");
			h.assertFalse(p.hasEffect(MobEffects.BLINDNESS), "denied effect absent");
			h.assertTrue(probe.count(p, Hook.ALLOW_EFFECT) >= 1, "allowEffect (ALLOW_ADD)");

			p.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 0));
			h.assertValueEqual(p.getEffect(MobEffects.SPEED).getAmplifier(), AbsorbCaps.EFFECT_AMPLIFIER_MAX, "modifyEffect +10 capped at 4");
			h.assertTrue(probe.count(p, Hook.MODIFY_EFFECT) >= 1, "modifyEffect");
			h.assertFalse(TraitEngine.isOwnedEffect(p, p.getEffect(MobEffects.SPEED)), "a normal effect is not owned");

			MobEffectInstance haste = new MobEffectInstance(MobEffects.HASTE, 400, 0, true, false, true);
			TraitEngine.addOwnedEffect(p, haste, src);
			h.assertValueEqual(p.getEffect(MobEffects.HASTE).getAmplifier(), 0, "owned effects are never modified");
			h.assertTrue(TraitEngine.isOwnedEffect(p, p.getEffect(MobEffects.HASTE)), "owned effect recognized");
			TraitEngine.addOwnedEffect(p, new MobEffectInstance(MobEffects.BLINDNESS, 100, 0, true, false, true), src);
			h.assertTrue(p.hasEffect(MobEffects.BLINDNESS), "owned effects skip immunities (weakness effects)");
			h.assertTrue(PlayerData.runtime(p).ownedEffects.get(Identifier.withDefaultNamespace("blindness")).equals(src), "owner recorded");

			// a potion of the same effect replacing ours is not ours any more
			p.addEffect(new MobEffectInstance(MobEffects.HASTE, 100, 1));
			h.assertFalse(TraitEngine.isOwnedEffect(p, p.getEffect(MobEffects.HASTE)), "a stronger potion is not owned");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void miscHooksFire(GameTestHelper h) throws ReflectiveOperationException {
		Probe probe = new Probe("misc");
		probe.xp = 2.0F;
		probe.durability = 3.0F;
		probe.visibility = 0.5;
		ServerPlayer p = player(h);
		ServerPlayer plain = player(h);
		ServerLevel level = level(h);
		try {
			give(p, 1, 0, source("misc", 0xFFFFFF, List.of(), List.of(probe.entry()), List.of(), List.of()));
			TraitEngine.recompute(plain);

			p.causeFallDamage(5.0, 1.0F, p.damageSources().fall());
			h.assertTrue(probe.count(p, Hook.LAND) == 1, "onLand (causeFallDamage)");
			near(h, probe.lastFall, 5.0, 1.0E-9, "fall distance passed");

			BlockPos pos = new BlockPos(0, 1, 0);
			h.setBlock(pos, Blocks.DIRT);
			h.assertTrue(p.gameMode.destroyBlock(h.absolutePos(pos)), "block broken");
			h.assertTrue(probe.count(p, Hook.BLOCK_BREAK) == 1, "onBlockBreak (PlayerBlockBreakEvents.AFTER)");

			Projectile snowball = (Projectile) EntityTypes.SNOWBALL.create(level, EntitySpawnReason.TRIGGERED);
			snowball.setPos(p.position());
			Method onHit = Projectile.class.getDeclaredMethod("onHit", HitResult.class);
			onHit.setAccessible(true);
			onHit.invoke(snowball, new EntityHitResult(p));
			h.assertTrue(probe.count(p, Hook.HIT_BY_PROJECTILE) == 1, "onHitByProjectile (zero-damage snowball)");

			int xp = p.totalExperience;
			ExperienceOrb orb = new ExperienceOrb(level, p.getX(), p.getY(), p.getZ(), 10);
			orb.playerTouch(p);
			h.assertValueEqual(p.totalExperience - xp, 20, "experienceFactor ×2 on orbs");
			int plainXp = plain.totalExperience;
			plain.giveExperiencePoints(10);
			h.assertValueEqual(plain.totalExperience - plainXp, 10, "players without the trait unchanged");

			ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
			sword.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
			h.assertValueEqual(sword.getDamageValue(), 3, "durabilityFactor ×3");

			Mob looker = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(1, 1, 1));
			double seen = visibility(p, looker);
			double seenPlain = visibility(plain, looker);
			near(h, seen, seenPlain * 0.5, 1.0E-9, "visibilityFactor ×0.5");
			h.assertTrue(probe.count(p, Hook.VISIBILITY) >= 1, "visibilityFactor reached");
			looker.discard();
		} finally {
			remove(p);
			remove(plain);
		}
		h.succeed();
	}

	@GameTest
	public void movementFlagsAndAura(GameTestHelper h) {
		Probe probe = new Probe("movement");
		probe.movement = new MovementState(MovementFlags.WALK_ON_WATER | MovementFlags.CLIMB_WALLS | MovementFlags.GLIDE, 0.12F, 0.0F);
		ServerPlayer p = player(h);
		try {
			give(p, 2, 0, source("movement", 0x336699, List.of(), List.of(probe.entry()), List.of(), List.of()));
			MovementState state = ((MovementFlagsHolder) p).absorbaholic$movement();
			h.assertTrue(state.equals(probe.movement), "movement state on the server player");
			h.assertTrue(state.equals(PlayerData.runtime(p).sentMovement), "movement state marked as synced");

			h.assertTrue(p.canStandOnFluid(Fluids.WATER.getSource(false)), "WALK_ON_WATER");
			h.assertFalse(p.canStandOnFluid(Fluids.LAVA.getSource(false)), "no WALK_ON_LAVA");
			p.setShiftKeyDown(true);
			h.assertFalse(p.canStandOnFluid(Fluids.WATER.getSource(false)), "sneaking sinks");
			p.setShiftKeyDown(false);

			p.horizontalCollision = true;
			h.assertTrue(p.onClimbable(), "CLIMB_WALLS against a wall");
			p.horizontalCollision = false;
			h.assertFalse(p.onClimbable(), "not without a wall");

			p.setOnGround(false);
			h.assertTrue(p.tryToStartFallFlying(), "GLIDE without an elytra (EntityElytraEvents.CUSTOM)");
			p.stopFallFlying();

			PlayerRuntime rt = PlayerData.runtime(p);
			h.assertValueEqual(rt.sentAuraStrength, 2.0F / AbsorbCaps.AURA_FULL_STRENGTH_LEVELS, "aura strength from trait levels");
			h.assertValueEqual(rt.sentAuraColor, 0x336699, "aura colour of the only source");

			withSources(() -> {
				TestSupport.setMode(h, false);
				try {
					TraitEngine.recompute(p);
				} finally {
					TestSupport.setMode(h, true);
				}
			});
			h.assertTrue(((MovementFlagsHolder) p).absorbaholic$movement().equals(MovementState.NONE), "dormant → no movement flags");
			h.assertValueEqual(rt.sentAuraStrength, 0.0F, "dormant → no aura");
			h.assertFalse(p.canStandOnFluid(Fluids.WATER.getSource(false)), "dormant → no water walking");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest(maxTicks = 60)
	public void deathHooksAndWipeSurviveDeath(GameTestHelper h) {
		Probe probe = new Probe("death");
		ServerPlayer p = player(h);
		SourceDefinition egg = source("death", 0xFFFFFF, List.of(), List.of(probe.entry()), List.of(),
				List.of(new BehaviorEntry<>(WipeOnDeathBehavior.TYPE, Unit.INSTANCE)));
		give(p, 1, 1, egg);
		h.assertTrue(TraitEngine.wipesOnDeath(p), "wipe_on_death active");
		p.hurtServer(level(h), p.damageSources().genericKill(), Float.MAX_VALUE);
		h.assertFalse(p.isAlive(), "dead");
		h.assertTrue(probe.count(p, Hook.DEATH) == 1, "onDeath (AFTER_DEATH)");
		h.runAfterDelay(3, () -> {
			h.assertTrue(TraitEngine.wipesOnDeath(p), "the dead player's cached set still wipes (COPY_FROM runs at respawn)");
			h.assertTrue(probe.count(p, Hook.DEACTIVATE) == 1, "entries deactivated once on death");
			remove(p);
			h.succeed();
		});
	}

	@GameTest
	public void endExitWithSharedRuntimeRebuildsTheNewEntity(GameTestHelper h) {
		Probe probe = new Probe("end_exit");
		ServerPlayer old = player(h);
		ServerPlayer fresh = player(h);
		SourceDefinition s = source("end_exit", 0xFFFFFF,
				List.of(attr(Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, 0.5)), List.of(probe.entry()), List.of(), List.of());
		try {
			give(old, 1, 0, s);
			// Fabric hands the new entity the old runtime object on an End exit (transient attachment copied on restoreAll)
			fresh.setAttached(PlayerData.RUNTIME, PlayerData.runtime(old));
			PlayerData.setTraits(fresh, PlayerData.traits(old));
			withSources(() -> {
				ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(old, fresh, true);
				TraitEngine.recompute(fresh);
			}, s);
			h.assertTrue(probe.count(fresh, Hook.ACTIVATE) == 1, "entries activate on the new entity");
			near(h, fresh.getAttributeValue(Attributes.MOVEMENT_SPEED), 0.15, 1.0E-6, "modifiers applied to the new entity");
		} finally {
			remove(old);
			remove(fresh);
		}
		h.succeed();
	}

	@GameTest
	public void brokenBehaviorsNeverEscape(GameTestHelper h) throws ReflectiveOperationException {
		Probe broken = new Probe("broken");
		broken.throwing = true;
		Probe brokenMovement = new Probe("broken_movement");
		brokenMovement.throwMovement = true;
		Probe healthy = new Probe("healthy");
		healthy.fires = true;
		ServerPlayer p = player(h);
		ServerLevel level = level(h);
		try {
			give(p, 1, 0, source("broken", 0xFFFFFF, List.of(), List.of(broken.entry(), brokenMovement.entry(), healthy.entry()), List.of(), List.of()));
			h.assertTrue(TraitEngine.active(p).all().size() == 2, "an entry whose movement() throws is dropped, the rest stays");
			h.assertTrue(broken.count(p, Hook.ACTIVATE) == 1 && healthy.count(p, Hook.ACTIVATE) == 1, "activation continues after a failure");

			p.hurtServer(level, p.damageSources().magic(), 2.0F);
			p.heal(1.0F);
			p.causeFoodExhaustion(1.0F);
			p.addEffect(new MobEffectInstance(MobEffects.SPEED, 100));
			TraitEngine.preventsTargeting(h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(1, 1, 1)), p);
			TraitEngine.visibilityFactor(p, null);
			TraitEngine.onLand(p, 3.0);
			TraitEngine.onItemConsumed(p, new ItemStack(Items.BREAD));
			TraitEngine.modifyFood(p, new ItemStack(Items.BREAD), new FoodProperties(5, 6.0F, false));
			TraitEngine.modifyExperience(p, 5);
			TraitEngine.modifyDurabilityDamage(p, new ItemStack(Items.DIAMOND_SWORD), 1);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_JUMP, null), "the healthy entry fires after the broken one threw");
			h.assertTrue(TraitEngine.modifyHeal(p, 2.0F, false) == 2.0F, "a throwing factor counts as no change");

			for (Hook hook : List.of(Hook.INCOMING_DAMAGE, Hook.ATTACKED, Hook.HEAL, Hook.EXHAUSTION, Hook.MODIFY_EFFECT, Hook.ALLOW_EFFECT,
					Hook.VISIBILITY, Hook.LAND, Hook.ITEM_CONSUMED, Hook.MODIFY_FOOD, Hook.EXPERIENCE, Hook.DURABILITY, Hook.SNEAK_JUMP)) {
				h.assertTrue(broken.count(p, hook) >= 1, "broken entry was offered " + hook);
				h.assertTrue(healthy.count(p, hook) >= 1, "healthy entry still reached " + hook);
			}
			PlayerData.setTraits(p, PlayerTraits.EMPTY);
			TraitEngine.recompute(p);
			h.assertTrue(broken.count(p, Hook.DEACTIVATE) == 1 && healthy.count(p, Hook.DEACTIVATE) == 1, "deactivation continues after a failure");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- triggers --------------------------------------------------------------------------------------------

	@GameTest(maxTicks = 100)
	public void jumpOnGroundFiresOnJump(GameTestHelper h) {
		driveInput(h, "jump", tick -> new Input(false, false, false, false, tick % 2 == 0, false, false), true, Hook.JUMP,
				List.of(Hook.SNEAK_JUMP, Hook.AIR_JUMP, Hook.SNEAK_DOUBLE_TAP));
	}

	@GameTest(maxTicks = 100)
	public void sneakJumpTrigger(GameTestHelper h) {
		driveInput(h, "sneak_jump", tick -> new Input(false, false, false, false, tick % 2 == 0, true, false), true, Hook.SNEAK_JUMP,
				List.of(Hook.JUMP, Hook.AIR_JUMP));
	}

	@GameTest(maxTicks = 100)
	public void airJumpTrigger(GameTestHelper h) {
		driveInput(h, "air_jump", tick -> new Input(false, false, false, false, tick % 2 == 0, false, false), false, Hook.AIR_JUMP,
				List.of(Hook.JUMP, Hook.SNEAK_JUMP));
	}

	@GameTest(maxTicks = 100)
	public void sneakDoubleTapTrigger(GameTestHelper h) {
		driveInput(h, "double_tap", tick -> new Input(false, false, false, false, false, tick % 2 == 0, false), true, Hook.SNEAK_DOUBLE_TAP,
				List.of(Hook.JUMP, Hook.SNEAK_JUMP, Hook.AIR_JUMP));
	}

	@GameTest
	public void sneakSwingFromSwingPacket(GameTestHelper h) throws ReflectiveOperationException {
		Probe probe = new Probe("swing");
		ServerPlayer p = player(h);
		try {
			give(p, 1, 0, source("swing", 0xFFFFFF, List.of(), List.of(probe.entry()), List.of(), List.of()));
			Vec3 at = h.absoluteVec(new Vec3(1.5, 1.0, 1.5));
			p.snapTo(at.x, at.y, at.z, 0.0F, -90.0F); // looking straight up into open air

			swingPacket(p);
			h.assertTrue(probe.count(p, Hook.SNEAK_SWING) == 0, "no trigger without sneaking");

			p.setShiftKeyDown(true);
			BlockPos above = new BlockPos(1, 3, 1);
			h.setBlock(above, Blocks.STONE);
			swingPacket(p);
			h.assertTrue(probe.count(p, Hook.SNEAK_SWING) == 0, "no trigger with a block in reach (mining / placing)");
			h.setBlock(above, Blocks.AIR);

			swingPacket(p);
			h.assertTrue(probe.count(p, Hook.SNEAK_SWING) == 1, "sneak + swing at air fires (swing mixin → onSwing)");
			swingPacket(p);
			h.assertTrue(probe.count(p, Hook.SNEAK_SWING) == 1, "rate limited to one per 4 ticks");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void oneTriggerFiresOneAbility(GameTestHelper h) {
		Probe first = new Probe("first");
		first.fires = false; // on cooldown: does not fire, the next one gets the trigger
		Probe second = new Probe("second");
		Probe third = new Probe("third");
		Probe exhaustion = new Probe("exhaustion_seen");
		exhaustion.fires = false;
		ServerPlayer p = player(h);
		try {
			give(p, 1, 0, source("one_trigger", 0xFFFFFF, List.of(),
					List.of(exhaustion.entry(), first.entry(), second.entry(), third.entry()), List.of(), List.of()));
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_JUMP, null), "fired");
			h.assertTrue(first.count(p, Hook.SNEAK_JUMP) == 1, "offered to the first (not ready)");
			h.assertTrue(second.count(p, Hook.SNEAK_JUMP) == 1, "the first ready one fires");
			h.assertTrue(third.count(p, Hook.SNEAK_JUMP) == 0, "only one ability per trigger");
			h.assertTrue(exhaustion.exhaustionSeen.equals(List.of(AbsorbCaps.ABILITY_EXHAUSTION)), "ability exhaustion charged once");
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "air jump fired");
			h.assertTrue(exhaustion.exhaustionSeen.get(1) == AbsorbCaps.AIR_JUMP_EXHAUSTION, "air jump costs less");
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.TICK, null), "not a trigger");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- helpers ---------------------------------------------------------------------------------------------

	/** Drives the player's client input every tick until {@code expected} fired and none of {@code never} did. */
	private static void driveInput(GameTestHelper h, String name, IntFunction<Input> input, boolean onGround, Hook expected, List<Hook> never) {
		Probe probe = new Probe("input_" + name);
		ServerPlayer p = player(h);
		SourceDefinition src = source("input_" + name, 0xFFFFFF, List.of(), List.of(probe.entry()), List.of(), List.of());
		give(p, 1, 0, src);
		keep(h, p, 1, 0, src);
		int[] tick = {0};
		h.onEachTick(() -> {
			p.setOnGround(onGround);
			p.setLastClientInput(input.apply(tick[0]++));
		});
		h.succeedWhen(() -> {
			for (Hook hook : never) h.assertTrue(probe.count(p, hook) == 0, hook + " must not fire");
			h.assertTrue(probe.count(p, expected) >= 1, expected + " fired");
			h.assertTrue(probe.count(p, Hook.TICK) >= 1, "tick hook dispatched");
			remove(p);
		});
	}

	private static ServerPlayer player(GameTestHelper h) {
		TestSupport.setMode(h, true);
		ServerPlayer p = TestSupport.survivalPlayer(h);
		Vec3 at = h.absoluteVec(new Vec3(1.5, 1.0, 1.5));
		p.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
		return p;
	}

	private static void remove(ServerPlayer p) {
		if (!p.hasDisconnected() && p.level().getServer().getPlayerList().getPlayer(p.getUUID()) == p) {
			p.level().getServer().getPlayerList().remove(p);
		}
	}

	private static void near(GameTestHelper h, double actual, double expected, double eps, String message) {
		h.assertTrue(Math.abs(actual - expected) <= eps, message + ": expected " + expected + ", got " + actual);
	}

	private static ServerLevel level(GameTestHelper h) {
		return h.getLevel();
	}

	/** A test source matching nothing (so other tests' lookups never see it). */
	private static SourceDefinition source(String path, int color, List<AttributeEntry> traitAttributes, List<BehaviorEntry<?>> traitBehaviors,
			List<AttributeEntry> weaknessAttributes, List<BehaviorEntry<?>> weaknessBehaviors) {
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", "engine/" + path),
				new SourceTargets(SourceKind.BLOCK, List.of(), List.of()), Optional.empty(), color, Tier.COMMON, 3,
				new SourceDefinition.Side("test_trait", traitAttributes, traitBehaviors),
				new SourceDefinition.Side("test_weakness", weaknessAttributes, weaknessBehaviors));
	}

	private static AttributeEntry attr(Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, AttributeModifier.Operation op, double amount) {
		return new AttributeEntry(attribute, op, new LevelValue.Scaled(amount));
	}

	private static BehaviorEntry<DamageMultiplierBehavior.Params> damageMultiplier(double multiplier) {
		return new BehaviorEntry<>(DamageMultiplierBehavior.TYPE, new DamageMultiplierBehavior.Params(Optional.empty(), List.of(),
				TargetFilter.ANY, Condition.ALWAYS, new LevelValue.PerLevel(List.of(multiplier, multiplier, multiplier))));
	}

	/** Runs {@code body} with {@code sources} added to the registry (restored afterwards, synchronously). */
	private static void withSources(Runnable body, SourceDefinition... sources) {
		List<SourceDefinition> before = SourceRegistry.all();
		List<SourceDefinition> with = new ArrayList<>(before);
		for (SourceDefinition s : sources) {
			with.removeIf(d -> d.id().equals(s.id()));
			with.add(s);
		}
		SourceRegistry.set(with);
		try {
			body.run();
		} finally {
			SourceRegistry.set(before);
		}
	}

	/** Gives the player {@code sources} at the given levels and rebuilds synchronously. */
	private static void give(ServerPlayer p, int traitLevel, int weaknessLevel, SourceDefinition... sources) {
		withSources(() -> {
			PlayerTraits traits = PlayerTraits.EMPTY;
			for (SourceDefinition s : sources) traits = traits.with(new TraitEntry(s.id(), traitLevel, weaknessLevel, false, false));
			PlayerData.setTraits(p, traits);
			TraitEngine.recompute(p);
		}, sources);
	}

	/** Re-applies the test sources whenever another test's mode toggle made the engine rebuild without them. */
	private static void keep(GameTestHelper h, ServerPlayer p, int traitLevel, int weaknessLevel, SourceDefinition... sources) {
		h.onEachTick(() -> {
			if (!p.isAlive() || p.hasDisconnected()) return;
			boolean missing = TraitEngine.active(p).all().stream().noneMatch(a -> a.sourceId().equals(sources[0].id()));
			if (missing || PlayerData.runtime(p).dirty) give(p, traitLevel, weaknessLevel, sources);
		});
	}

	private static void assertNoModifiers(GameTestHelper h, ServerPlayer p, String when) {
		for (Holder<net.minecraft.world.entity.ai.attributes.Attribute> a : List.of(Attributes.MOVEMENT_SPEED, Attributes.MAX_HEALTH)) {
			var inst = p.getAttribute(a);
			for (Identifier id : AbsorbCapsIds.ALL) h.assertTrue(inst.getModifier(id) == null, when + ": modifier " + id + " removed from " + a.getRegisteredName());
		}
		near(h, p.getAttributeValue(Attributes.MOVEMENT_SPEED), 0.1, 1.0E-6, when + ": vanilla speed");
		h.assertValueEqual(p.getMaxHealth(), 20.0F, when + ": vanilla max health");
	}

	/** Calls the version's swing handler: 26.2 {@code handleAnimate(ServerboundSwingPacket)}, 26.3 {@code handlePunch(ServerboundPunchPacket)}. */
	private static void swingPacket(ServerPlayer p) throws ReflectiveOperationException {
		for (Method m : ServerGamePacketListenerImpl.class.getMethods()) {
			if (m.getParameterCount() != 1 || !(m.getName().equals("handleAnimate") || m.getName().equals("handlePunch"))) continue;
			Class<?> packetType = m.getParameterTypes()[0];
			Object packet;
			try {
				packet = packetType.getField("INSTANCE").get(null);
			} catch (NoSuchFieldException e) {
				packet = packetType.getConstructor(InteractionHand.class).newInstance(InteractionHand.MAIN_HAND);
			}
			m.invoke(p.connection, packet);
			return;
		}
		throw new IllegalStateException("no swing packet handler found");
	}

	/** {@code getVisibilityPercent}: 26.2 {@code (Entity)}, 26.3 {@code (ServerLevel, Entity)}. */
	private static double visibility(ServerPlayer p, Entity looker) throws ReflectiveOperationException {
		for (Method m : LivingEntity.class.getMethods()) {
			if (!m.getName().equals("getVisibilityPercent")) continue;
			return (double) (m.getParameterCount() == 1 ? m.invoke(p, looker) : m.invoke(p, p.level(), looker));
		}
		throw new IllegalStateException("no getVisibilityPercent");
	}

	/** Our modifier ids (mirrors AttributeApplier's package-private constants). */
	private static final class AbsorbCapsIds {
		static final Identifier CLAMP = Identifier.fromNamespaceAndPath("absorbaholic", "clamp");
		static final List<Identifier> ALL = List.of(Identifier.fromNamespaceAndPath("absorbaholic", "add_value"),
				Identifier.fromNamespaceAndPath("absorbaholic", "add_multiplied_base"),
				Identifier.fromNamespaceAndPath("absorbaholic", "add_multiplied_total"), CLAMP);
	}

	/**
	 * Test-only behavior overriding every hook: counts calls per player and hook, and returns configurable factors /
	 * results. Never registered (built with {@link BehaviorType#of}); each test uses its own instance.
	 */
	static final class Probe implements Behavior<Unit> {
		final BehaviorType<Unit> type;
		private final Map<UUID, EnumMap<Hook, Integer>> calls = new HashMap<>();
		final List<Boolean> naturalHeals = new ArrayList<>();
		final List<Float> exhaustionSeen = new ArrayList<>();
		@Nullable ItemStack consumed;
		double lastFall;
		boolean fires = true;
		boolean throwing;
		boolean throwMovement;
		boolean immuneToAll;
		boolean preventTargeting;
		float incoming = 1.0F;
		float outgoing = 1.0F;
		float heal = 1.0F;
		float exhaustion = 1.0F;
		float xp = 1.0F;
		float durability = 1.0F;
		double visibility = 1.0;
		int amplify;
		@Nullable Holder<MobEffect> deny;
		@Nullable FoodProperties food;
		MovementState movement = MovementState.NONE;

		Probe(String name) {
			type = BehaviorType.of(Identifier.fromNamespaceAndPath("absorbaholic_test", "probe/" + name), MapCodec.unit(Unit.INSTANCE), this);
		}

		BehaviorEntry<Unit> entry() {
			return new BehaviorEntry<>(type, Unit.INSTANCE);
		}

		int count(ServerPlayer p, Hook hook) {
			EnumMap<Hook, Integer> m = calls.get(p.getUUID());
			return m == null ? 0 : m.getOrDefault(hook, 0);
		}

		private void hit(ServerPlayer p, Hook hook) {
			calls.computeIfAbsent(p.getUUID(), k -> new EnumMap<>(Hook.class)).merge(hook, 1, Integer::sum);
			if (throwing) throw new IllegalStateException("probe failure in " + hook);
		}

		@Override
		public void tick(ActiveBehavior<Unit> self, ServerPlayer player) {
			hit(player, Hook.TICK);
		}

		@Override
		public void onActivate(ActiveBehavior<Unit> self, ServerPlayer player) {
			hit(player, Hook.ACTIVATE);
		}

		@Override
		public void onDeactivate(ActiveBehavior<Unit> self, ServerPlayer player) {
			hit(player, Hook.DEACTIVATE);
		}

		@Override
		public MovementState movement(ActiveBehavior<Unit> self) {
			if (throwMovement) throw new IllegalStateException("probe failure in movement");
			return movement;
		}

		@Override
		public boolean isImmuneTo(ActiveBehavior<Unit> self, ServerPlayer player, DamageSource source) {
			hit(player, Hook.IMMUNITY);
			return immuneToAll;
		}

		@Override
		public float incomingDamageFactor(ActiveBehavior<Unit> self, ServerPlayer player, DamageSource source, float amount) {
			hit(player, Hook.INCOMING_DAMAGE);
			return incoming;
		}

		@Override
		public float outgoingDamageFactor(ActiveBehavior<Unit> self, ServerPlayer player, LivingEntity target, DamageSource source, float amount) {
			hit(player, Hook.OUTGOING_DAMAGE);
			return outgoing;
		}

		@Override
		public void onDealtDamage(ActiveBehavior<Unit> self, ServerPlayer player, LivingEntity target, DamageSource source, float amount) {
			hit(player, Hook.DEALT_DAMAGE);
		}

		@Override
		public void onAttacked(ActiveBehavior<Unit> self, ServerPlayer player, DamageSource source, float amount) {
			hit(player, Hook.ATTACKED);
		}

		@Override
		public void onKill(ActiveBehavior<Unit> self, ServerPlayer player, LivingEntity victim) {
			hit(player, Hook.KILL);
		}

		@Override
		public void onJump(ActiveBehavior<Unit> self, ServerPlayer player) {
			hit(player, Hook.JUMP);
		}

		@Override
		public boolean onSneakJump(ActiveBehavior<Unit> self, ServerPlayer player) {
			hit(player, Hook.SNEAK_JUMP);
			return fires;
		}

		@Override
		public boolean onAirJump(ActiveBehavior<Unit> self, ServerPlayer player) {
			hit(player, Hook.AIR_JUMP);
			return fires;
		}

		@Override
		public boolean onSneakDoubleTap(ActiveBehavior<Unit> self, ServerPlayer player) {
			hit(player, Hook.SNEAK_DOUBLE_TAP);
			return fires;
		}

		@Override
		public boolean onSneakSwing(ActiveBehavior<Unit> self, ServerPlayer player, @Nullable Entity target) {
			hit(player, Hook.SNEAK_SWING);
			return fires;
		}

		@Override
		public void onSneakAttack(ActiveBehavior<Unit> self, ServerPlayer player, Entity target) {
			hit(player, Hook.SNEAK_ATTACK);
		}

		@Override
		public void onLand(ActiveBehavior<Unit> self, ServerPlayer player, double fallDistance) {
			lastFall = fallDistance;
			hit(player, Hook.LAND);
		}

		@Override
		public void onDeath(ActiveBehavior<Unit> self, ServerPlayer player, DamageSource source) {
			hit(player, Hook.DEATH);
		}

		@Override
		public float healFactor(ActiveBehavior<Unit> self, ServerPlayer player, float amount, boolean natural) {
			naturalHeals.add(natural);
			hit(player, Hook.HEAL);
			return heal;
		}

		@Override
		public float exhaustionFactor(ActiveBehavior<Unit> self, ServerPlayer player, float amount) {
			exhaustionSeen.add(amount);
			hit(player, Hook.EXHAUSTION);
			return exhaustion;
		}

		@Override
		public boolean allowEffect(ActiveBehavior<Unit> self, ServerPlayer player, MobEffectInstance effect) {
			hit(player, Hook.ALLOW_EFFECT);
			return deny == null || !effect.is(deny);
		}

		@Override
		public MobEffectInstance modifyEffect(ActiveBehavior<Unit> self, ServerPlayer player, MobEffectInstance effect) {
			hit(player, Hook.MODIFY_EFFECT);
			if (amplify == 0) return effect;
			return new MobEffectInstance(effect.getEffect(), effect.getDuration(), effect.getAmplifier() + amplify, effect.isAmbient(),
					effect.isVisible(), effect.showIcon());
		}

		@Override
		public boolean preventsTargeting(ActiveBehavior<Unit> self, ServerPlayer player, Mob mob) {
			hit(player, Hook.PREVENT_TARGETING);
			return preventTargeting;
		}

		@Override
		public double visibilityFactor(ActiveBehavior<Unit> self, ServerPlayer player, @Nullable Entity looker) {
			hit(player, Hook.VISIBILITY);
			return visibility;
		}

		@Override
		public void onBlockBreak(ActiveBehavior<Unit> self, ServerPlayer player, BlockPos pos, BlockState state) {
			hit(player, Hook.BLOCK_BREAK);
		}

		@Override
		public void onHitByProjectile(ActiveBehavior<Unit> self, ServerPlayer player, Projectile projectile) {
			hit(player, Hook.HIT_BY_PROJECTILE);
		}

		@Override
		public FoodProperties modifyFood(ActiveBehavior<Unit> self, ServerPlayer player, ItemStack stack, FoodProperties food) {
			hit(player, Hook.MODIFY_FOOD);
			return this.food == null ? food : this.food;
		}

		@Override
		public void onItemConsumed(ActiveBehavior<Unit> self, ServerPlayer player, ItemStack stack) {
			consumed = stack;
			hit(player, Hook.ITEM_CONSUMED);
		}

		@Override
		public float experienceFactor(ActiveBehavior<Unit> self, ServerPlayer player, int amount) {
			hit(player, Hook.EXPERIENCE);
			return xp;
		}

		@Override
		public float durabilityFactor(ActiveBehavior<Unit> self, ServerPlayer player, ItemStack stack, int amount) {
			hit(player, Hook.DURABILITY);
			return durability;
		}
	}
}
