package dev.absorbaholic.test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.SourceSpec;
import dev.absorbaholic.core.SourceSpecParser;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceLoader;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.registry.SourceResolver;
import dev.absorbaholic.registry.SourceTargets;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.BehaviorEntry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Hook;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.trait.behavior.AuraBehavior;
import dev.absorbaholic.trait.behavior.DetectionRangeBehavior;
import dev.absorbaholic.trait.behavior.EffectModifierBehavior;
import dev.absorbaholic.trait.behavior.EnvironmentDamageBehavior;
import dev.absorbaholic.trait.behavior.FoodModifierBehavior;
import dev.absorbaholic.trait.behavior.HealMultiplierBehavior;
import dev.absorbaholic.trait.behavior.HealOverTimeBehavior;
import dev.absorbaholic.trait.behavior.HungerDrainBehavior;
import dev.absorbaholic.trait.behavior.ItemMagnetBehavior;
import dev.absorbaholic.trait.behavior.MobAttitudeBehavior;
import dev.absorbaholic.trait.behavior.StatusEffectBehavior;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * WP-BEH-B server gametests: one test per behavior type (status_effect, environment_damage, heal_multiplier,
 * heal_over_time, hunger_drain, food_modifier, effect_modifier, aura, mob_attitude ×3 attitudes, detection_range,
 * item_magnet), each proving the effect with concrete numbers through the real engine wiring (TraitEngine active set,
 * vanilla calls that reach the engine's mixins / events, and the engine's own tick scheduling for heal_over_time and
 * the status_effect pulse). Wherever possible the params are the shipped source JSON's (only this package's behavior
 * entries are kept, so other packages' types and the sources' attributes don't disturb the numbers), plus a check that
 * every shipped source using these types resolves.
 *
 * <p>Test sources have no targets and live in {@link SourceRegistry} only inside synchronous blocks (the registry is
 * shared by parallel tests). Condition results are cached for 10 ticks; rebuilding the set ({@link #refresh})
 * re-evaluates them.
 */
public class BehaviorBGameTests {
	private static final double EPS = 1.0E-4;
	private static final Set<Identifier> TYPES = Set.of(StatusEffectBehavior.TYPE.id(), EnvironmentDamageBehavior.TYPE.id(),
			HealMultiplierBehavior.TYPE.id(), HealOverTimeBehavior.TYPE.id(), HungerDrainBehavior.TYPE.id(), FoodModifierBehavior.TYPE.id(),
			EffectModifierBehavior.TYPE.id(), AuraBehavior.TYPE.id(), MobAttitudeBehavior.TYPE.id(), DetectionRangeBehavior.TYPE.id(),
			ItemMagnetBehavior.TYPE.id());

	// ---- shipped data --------------------------------------------------------------------------------------------

	/** Every shipped source that uses this package's types resolves them (params, level arrays, ids, conditions). */
	@GameTest
	public void shippedSourcesWithBehaviorBTypesResolve(GameTestHelper h) {
		List<String> problems = new ArrayList<>();
		int files = 0;
		int entries = 0;
		for (SourceLoader.RawSource raw : SourceLoader.read(h.getLevel().getServer().getResourceManager())) {
			if (!(raw.parsed() instanceof SourceSpecParser.Result.Parsed(SourceSpec spec))) continue;
			int mine = countMine(spec.trait()) + countMine(spec.weakness());
			if (mine == 0) continue;
			files++;
			entries += mine;
			SourceSpec onlyMine = new SourceSpec(spec.kind(), spec.targets(), spec.name(), spec.icon(), spec.color(), spec.tier(), spec.maxLevel(),
					keepMine(spec.trait(), true), keepMine(spec.weakness(), true));
			List<String> errors = SourceResolver.errorsOf(raw.id(), onlyMine);
			if (!errors.isEmpty()) problems.add(raw.id() + ": " + String.join("; ", errors));
		}
		// the real load never skips a source because of this package's types
		SourceLoader.lastResult().skipped().forEach((id, skipped) -> {
			for (Identifier type : skipped.unknownBehaviorTypes()) {
				if (TYPES.contains(type)) problems.add(id + " skipped for " + type);
			}
		});
		h.assertTrue(problems.isEmpty(), "shipped sources with WP-BEH-B types: " + String.join(" | ", problems));
		h.assertTrue(files >= 80 && entries >= 120, "shipped usage found: " + files + " files, " + entries + " entries");
		h.succeed();
	}

	// ---- status_effect -------------------------------------------------------------------------------------------

	@GameTest
	public void statusEffectKeepsOwnedEffectsOnlyWhileActive(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			// beacon trait: haste [1, 1, 2], regeneration [-1, -1, 0], speed [-1, 0, 1]
			SourceDefinition beacon = shipped(h, "beacon");
			give(p, 1, 0, beacon);
			tick(p, StatusEffectBehavior.TYPE);
			MobEffectInstance haste = p.getEffect(MobEffects.HASTE);
			h.assertTrue(haste != null && haste.getAmplifier() == 1, "beacon I: haste II");
			h.assertTrue(haste.isAmbient() && !haste.isVisible() && haste.showIcon() && haste.getDuration() >= 60, "ambient, no particles, icon, >= 60 ticks");
			h.assertTrue(TraitEngine.isOwnedEffect(p, haste), "haste is owned");
			h.assertFalse(p.hasEffect(MobEffects.SPEED) || p.hasEffect(MobEffects.REGENERATION), "amplifier -1 disables speed / regeneration at I");

			give(p, 3, 0, beacon);
			tick(p, StatusEffectBehavior.TYPE);
			h.assertValueEqual(p.getEffect(MobEffects.HASTE).getAmplifier(), 2, "beacon III: haste III");
			h.assertValueEqual(p.getEffect(MobEffects.SPEED).getAmplifier(), 1, "beacon III: speed II");
			h.assertValueEqual(p.getEffect(MobEffects.REGENERATION).getAmplifier(), 0, "beacon III: regeneration I");

			give(p, 0, 0);
			h.assertFalse(p.hasEffect(MobEffects.HASTE) || p.hasEffect(MobEffects.SPEED) || p.hasEffect(MobEffects.REGENERATION),
					"losing the trait removes our effects");

			// condition + a drunk potion is never removed + hand over between two sources wanting the same effect
			SourceDefinition sneaky = custom("sneaky", StatusEffectBehavior.TYPE, "{\"effect\": \"minecraft:night_vision\", \"amplifier\": [0], \"condition\": \"sneaking\"}", false);
			SourceDefinition always = custom("always_nv", StatusEffectBehavior.TYPE, "{\"effect\": \"minecraft:night_vision\", \"amplifier\": [0]}", false);
			p.setShiftKeyDown(true);
			give(p, 1, 0, sneaky);
			tick(p, StatusEffectBehavior.TYPE);
			h.assertTrue(p.getEffect(MobEffects.NIGHT_VISION) != null && p.getEffect(MobEffects.NIGHT_VISION).getDuration() >= 220,
					"night vision kept above 220 ticks (no flicker)");
			p.setShiftKeyDown(false);
			refresh(p, sneaky);
			tick(p, StatusEffectBehavior.TYPE);
			h.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION), "condition false: our night vision removed");

			p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, 0));
			tick(p, StatusEffectBehavior.TYPE);
			h.assertTrue(p.hasEffect(MobEffects.NIGHT_VISION), "a potion of the same effect is never removed");
			p.removeEffect(MobEffects.NIGHT_VISION);

			p.setShiftKeyDown(true);
			give(p, 1, 0, sneaky, always);
			tick(p, StatusEffectBehavior.TYPE);
			p.setShiftKeyDown(false);
			refresh(p, sneaky, always);
			tick(p, StatusEffectBehavior.TYPE);
			h.assertTrue(p.hasEffect(MobEffects.NIGHT_VISION) && TraitEngine.isOwnedEffect(p, p.getEffect(MobEffects.NIGHT_VISION)),
					"another active source keeps the shared effect");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	/** Pulse mode through the engine's own tick scheduling: darkness every 20 ticks for 40 ticks. */
	@GameTest(maxTicks = 120)
	public void statusEffectPulsesOnSchedule(GameTestHelper h) {
		ServerPlayer p = player(h);
		SourceDefinition pulse = custom("pulse", StatusEffectBehavior.TYPE,
				"{\"effect\": \"minecraft:darkness\", \"amplifier\": [0], \"interval\": [20], \"duration\": [40]}", true);
		give(p, 0, 1, pulse);
		h.assertFalse(p.hasEffect(MobEffects.DARKNESS), "no pulse before the first interval");
		keep(h, p, 0, 1, pulse);
		h.succeedWhen(() -> {
			MobEffectInstance darkness = p.getEffect(MobEffects.DARKNESS);
			h.assertTrue(darkness != null, "darkness pulse applied by the engine tick");
			h.assertTrue(darkness.getDuration() <= 40 && darkness.getDuration() > 20 && TraitEngine.isOwnedEffect(p, darkness),
					"pulse lasts 40 ticks and is owned: " + darkness.getDuration());
			remove(p);
		});
	}

	// ---- environment_damage --------------------------------------------------------------------------------------

	@GameTest
	public void environmentDamageHurtsThroughTheGateOrIgnites(GameTestHelper h) {
		ServerPlayer p = player(h);
		ServerPlayer burner = player(h);
		try {
			// bee weakness: 1.0 weakness damage every 20 ticks while starving
			give(p, 0, 1, shipped(h, "bee"));
			p.getFoodData().setFoodLevel(10);
			tick(p, EnvironmentDamageBehavior.TYPE);
			h.assertValueEqual(p.getHealth(), 20.0F, "not starving: no damage");
			p.getFoodData().setFoodLevel(0);
			refresh(p, shipped(h, "bee"));
			tick(p, EnvironmentDamageBehavior.TYPE);
			h.assertValueEqual(p.getHealth(), 19.0F, "starving: 1 HP of weakness damage");
			h.assertTrue(p.getLastDamageSource() != null && p.getLastDamageSource().is(dev.absorbaholic.trait.WeaknessDamage.TYPE),
					"damage type absorbaholic:weakness");

			// ignite mode with helmet_blocks (sunburn style), level 2 = 3 s
			SourceDefinition sunburn = custom("sunburn", EnvironmentDamageBehavior.TYPE, "{\"ignite_seconds\": [2, 3], \"helmet_blocks\": true}", true);
			give(burner, 0, 2, sunburn);
			ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
			burner.setItemSlot(EquipmentSlot.HEAD, helmet);
			tick(burner, EnvironmentDamageBehavior.TYPE);
			h.assertValueEqual(burner.getRemainingFireTicks() > 0, false, "a helmet blocks the ignition");
			h.assertValueEqual(burner.getItemBySlot(EquipmentSlot.HEAD).getDamageValue(), 1, "the helmet loses 1 durability");
			burner.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			tick(burner, EnvironmentDamageBehavior.TYPE);
			h.assertValueEqual(burner.getRemainingFireTicks(), 60, "no helmet: set on fire for 3 s");
		} finally {
			remove(p);
			remove(burner);
		}
		h.succeed();
	}

	// ---- heal_multiplier / heal_over_time ------------------------------------------------------------------------

	@GameTest
	public void healMultiplierScalesAllOrNaturalHealing(GameTestHelper h) {
		ServerPlayer p = player(h);
		ServerPlayer q = player(h);
		try {
			give(p, 3, 0, shipped(h, "flowers")); // heal ×1.35 (all)
			p.setHealth(10.0F);
			p.heal(2.0F);
			near(h, p.getHealth(), 12.7, "flowers III: heal 2 → 2.7");

			give(q, 0, 1, shipped(h, "cactus")); // natural heal ×0.75
			q.setHealth(10.0F);
			q.heal(2.0F);
			near(h, q.getHealth(), 12.0, "cactus weakness leaves a potion-style heal alone");
			q.getFoodData().setFoodLevel(18);
			q.getFoodData().setSaturation(0.0F);
			for (int i = 0; i < 80; i++) q.getFoodData().tick(q); // one natural regen heal of 1 HP
			near(h, q.getHealth(), 12.75, "cactus weakness I: natural regeneration 1 → 0.75");
		} finally {
			remove(p);
			remove(q);
		}
		h.succeed();
	}

	/** heal_over_time through the engine's tick scheduling: axolotl I heals 1 HP every 40 ticks at low health. */
	@GameTest(maxTicks = 120)
	public void healOverTimeHealsOnSchedule(GameTestHelper h) {
		ServerPlayer p = player(h);
		SourceDefinition axolotl = shipped(h, "axolotl");
		give(p, 1, 0, axolotl);
		p.setHealth(5.0F);
		keep(h, p, 1, 0, axolotl);
		h.succeedWhen(() -> {
			h.assertTrue(p.getHealth() > 5.0F, "healed by the engine tick");
			near(h, p.getHealth(), 6.0, "axolotl I: +1 HP per 40 ticks");
			remove(p);
		});
	}

	// ---- hunger_drain --------------------------------------------------------------------------------------------

	@GameTest
	public void hungerDrainScalesExhaustion(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			give(p, 3, 0, shipped(h, "camel")); // exhaustion ×0.5
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5.0F);
			p.causeFoodExhaustion(6.0F); // → 3
			p.causeFoodExhaustion(2.0F); // → 4 (not > 4 yet)
			p.getFoodData().tick(p);
			h.assertValueEqual(p.getFoodData().getSaturationLevel(), 5.0F, "8 exhaustion ×0.5 = 4 does not cost saturation");
			p.causeFoodExhaustion(0.2F); // → 4.1
			p.getFoodData().tick(p);
			h.assertValueEqual(p.getFoodData().getSaturationLevel(), 4.0F, "the next 0.1 does");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- food_modifier -------------------------------------------------------------------------------------------

	@GameTest
	public void foodModifierChangesMatchingFood(GameTestHelper h) {
		ServerPlayer p = player(h);
		ServerPlayer q = player(h);
		ServerLevel level = h.getLevel();
		try {
			give(p, 1, 0, shipped(h, "villager")); // farm foods ×1.25 / ×1.25
			p.getFoodData().setFoodLevel(10);
			p.getFoodData().setSaturation(0.0F);
			new ItemStack(Items.BREAD).finishUsingItem(level, p); // 5 / 6.0 → 6 / 6.0 × 6/5 × 1.25 = 9.0
			h.assertValueEqual(p.getFoodData().getFoodLevel(), 16, "bread: nutrition 5 → 6");
			near(h, p.getFoodData().getSaturationLevel(), 9.0, "bread: saturation 6 → 9");
			p.getFoodData().setFoodLevel(10);
			p.getFoodData().setSaturation(0.0F);
			new ItemStack(Items.COOKED_BEEF).finishUsingItem(level, p);
			h.assertValueEqual(p.getFoodData().getFoodLevel(), 18, "beef is not listed: unchanged");

			give(q, 0, 1, shipped(h, "parrot")); // parrot-poisonous food → poison I, 100 ticks
			new ItemStack(Items.COOKIE).finishUsingItem(level, q);
			MobEffectInstance poison = q.getEffect(MobEffects.POISON);
			h.assertTrue(poison != null && poison.getAmplifier() == 0 && poison.getDuration() == 100, "cookie → poison I for 100 ticks");
			q.removeEffect(MobEffects.POISON);
			new ItemStack(Items.BREAD).finishUsingItem(level, q);
			h.assertFalse(q.hasEffect(MobEffects.POISON), "bread is safe");
		} finally {
			remove(p);
			remove(q);
		}
		h.succeed();
	}

	// ---- effect_modifier -----------------------------------------------------------------------------------------

	@GameTest
	public void effectModifierImmuneDurationAmplifierInvert(GameTestHelper h) {
		ServerPlayer honey = player(h);
		ServerPlayer spider = player(h);
		ServerPlayer warden = player(h);
		ServerPlayer wither = player(h);
		ServerPlayer cow = player(h);
		try {
			give(honey, 3, 0, shipped(h, "honey")); // poison duration ×0.25
			honey.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
			h.assertValueEqual(honey.getEffect(MobEffects.POISON).getDuration(), 50, "honey III: poison 200 → 50 ticks");

			give(spider, 0, 3, shipped(h, "spider")); // poison amplifier +1 and duration ×2.5
			spider.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
			MobEffectInstance poison = spider.getEffect(MobEffects.POISON);
			h.assertTrue(poison.getAmplifier() == 1 && poison.getDuration() == 250, "spider weakness III: poison I/100 → II/250, got "
					+ poison.getAmplifier() + "/" + poison.getDuration());

			give(warden, 1, 0, shipped(h, "warden")); // immune to blindness and darkness
			h.assertFalse(warden.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0)), "warden: blindness refused");
			h.assertTrue(warden.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 0)), "other effects still apply");

			give(wither, 0, 1, shipped(h, "wither")); // instant_health inverted
			MobEffects.INSTANT_HEALTH.value().applyInstantaneousEffect(h.getLevel(), null, null, wither, 0, 1.0);
			near(h, wither.getHealth(), 14.0, "wither weakness: instant health I deals 6 weakness damage");
			h.assertTrue(wither.getLastDamageSource().is(dev.absorbaholic.trait.WeaknessDamage.TYPE), "inverted damage is weakness damage (gated)");

			// cow trait: harmful effects ×0.55 at III; never owned effects, ambient ones or infinite ones
			SourceDefinition slow = custom("owned_slowness", StatusEffectBehavior.TYPE, "{\"effect\": \"minecraft:slowness\", \"amplifier\": [0]}", true);
			give(cow, 3, 1, shipped(h, "cow"), slow);
			cow.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200, 0));
			h.assertValueEqual(cow.getEffect(MobEffects.WEAKNESS).getDuration(), 110, "cow III: weakness 200 → 110 ticks");
			cow.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 200, 0, true, true));
			h.assertValueEqual(cow.getEffect(MobEffects.MINING_FATIGUE).getDuration(), 200, "ambient (beacon) effects untouched");
			cow.addEffect(new MobEffectInstance(MobEffects.POISON, MobEffectInstance.INFINITE_DURATION, 0));
			h.assertTrue(cow.getEffect(MobEffects.POISON).isInfiniteDuration(), "infinite effects untouched");
			tick(cow, StatusEffectBehavior.TYPE);
			h.assertValueEqual(cow.getEffect(MobEffects.SLOWNESS).getDuration(), 100, "our own status_effect is never shortened");

			ServerPlayer pig = player(h);
			try {
				give(pig, 3, 0, shipped(h, "pig")); // hunger duration ×0 at III = immunity
				h.assertFalse(pig.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, 0)), "pig III: hunger ×0 refused");
			} finally {
				remove(pig);
			}
		} finally {
			for (ServerPlayer s : List.of(honey, spider, warden, wither, cow)) remove(s);
		}
		h.succeed();
	}

	// ---- aura ----------------------------------------------------------------------------------------------------

	@GameTest
	public void auraAppliesPayloadToMatchingMobsInRange(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			Mob near = h.spawn(EntityTypes.ZOMBIE, new Vec3(3.0, 1.0, 1.5)); // 1.5 blocks away
			Mob far = h.spawn(EntityTypes.ZOMBIE, new Vec3(6.5, 1.0, 6.5));
			Mob cow = h.spawn(EntityTypes.COW, new Vec3(1.5, 1.0, 3.0));
			near.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
			far.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));

			give(p, 1, 0, shipped(h, "sulfur")); // hostile within 2: weakness I for 60 ticks
			tick(p, AuraBehavior.TYPE);
			MobEffectInstance weakness = near.getEffect(MobEffects.WEAKNESS);
			h.assertTrue(weakness != null && weakness.getAmplifier() == 0 && weakness.getDuration() == 60, "near zombie: weakness I, 60 ticks");
			h.assertFalse(far.hasEffect(MobEffects.WEAKNESS), "zombie beyond the radius untouched");
			h.assertFalse(cow.hasEffect(MobEffects.WEAKNESS), "a cow is not hostile");
			h.assertFalse(p.hasEffect(MobEffects.WEAKNESS), "never the player");

			give(p, 1, 0, shipped(h, "glow_blocks")); // radius [0, 8, 16]
			tick(p, AuraBehavior.TYPE);
			h.assertFalse(near.hasEffect(MobEffects.GLOWING), "radius 0 disables the aura at level I");
			give(p, 2, 0, shipped(h, "glow_blocks"));
			tick(p, AuraBehavior.TYPE);
			h.assertTrue(near.hasEffect(MobEffects.GLOWING) && far.hasEffect(MobEffects.GLOWING), "level II: glowing hostiles within 8");
			h.assertFalse(cow.hasEffect(MobEffects.GLOWING), "glowing only on hostiles");

			give(p, 1, 0, shipped(h, "magma_block")); // ignite 2 s within 1 block
			tick(p, AuraBehavior.TYPE);
			h.assertValueEqual(near.getRemainingFireTicks(), 40, "magma block I: adjacent zombie burns for 2 s");
			h.assertValueEqual(far.getRemainingFireTicks() > 0, false, "far zombie not ignited");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- mob_attitude --------------------------------------------------------------------------------------------

	@GameTest
	public void mobAttitudeHostileHuntsAndCalmsDown(GameTestHelper h) {
		ServerPlayer chicken = player(h);
		ServerPlayer rabbit = player(h);
		ServerPlayer ender = player(h);
		try {
			Mob fox = h.spawn(EntityTypes.FOX, new Vec3(4.5, 1.0, 1.5));
			give(chicken, 0, 1, shipped(h, "chicken")); // foxes and ocelots within 16 hunt the player
			tick(chicken, MobAttitudeBehavior.TYPE);
			h.assertTrue(fox.getTarget() == chicken, "a fox hunts the chicken-absorber");

			TamableAnimal wolf = h.spawn(EntityTypes.WOLF, new Vec3(4.5, 1.0, 4.5));
			TamableAnimal pet = h.spawn(EntityTypes.WOLF, new Vec3(1.5, 1.0, 4.5));
			pet.tame(rabbit);
			give(rabbit, 0, 1, shipped(h, "rabbit")); // wolves and foxes
			tick(rabbit, MobAttitudeBehavior.TYPE);
			h.assertTrue(wolf.getTarget() == rabbit && ((NeutralMob) wolf).isAngryAt(rabbit, h.getLevel()), "a wild wolf is angry at the rabbit-absorber");
			h.assertTrue(pet.getTarget() == null, "the player's own wolf never turns on it");

			Mob enderman = h.spawn(EntityTypes.ENDERMAN, new Vec3(6.5, 1.0, 1.5));
			give(ender, 0, 1, shipped(h, "enderman")); // endermen within 16 are always hostile
			tick(ender, MobAttitudeBehavior.TYPE);
			h.assertTrue(enderman.getTarget() == ender && ((NeutralMob) enderman).isAngryAt(ender, h.getLevel()),
					"an enderman is hostile without being looked at");

			// the weakness goes away → the mobs it provoked calm down (no AI change is left behind)
			give(chicken, 0, 0);
			h.assertTrue(fox.getTarget() == null, "fox calms down when the weakness is gone");
			give(rabbit, 0, 0);
			h.assertTrue(wolf.getTarget() == null && !((NeutralMob) wolf).isAngryAt(rabbit, h.getLevel()), "wolf calms down");
		} finally {
			remove(chicken);
			remove(rabbit);
			remove(ender);
		}
		h.succeed();
	}

	@GameTest
	public void mobAttitudeFleeIronGolemFearAura(GameTestHelper h) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		}
		ServerPlayer p = player(h);
		ServerPlayer cat = player(h);
		try {
			SourceDefinition golem = shipped(h, "iron_golem"); // #absorbaholic:fears_golem flee within 4/6/8, pause_on_hit 100
			Mob husk = h.spawn(EntityTypes.HUSK, new Vec3(3.5, 1.0, 1.5));
			Mob other = h.spawn(EntityTypes.HUSK, new Vec3(1.5, 1.0, 3.5));
			Mob outside = h.spawn(EntityTypes.HUSK, new Vec3(6.5, 1.0, 6.5));
			Mob creeper = h.spawn(EntityTypes.CREEPER, new Vec3(3.5, 1.0, 3.5));
			give(p, 1, 0, golem);
			h.assertFalse(husk.canAttack(p), "a feared mob inside the radius cannot target the player");
			husk.setTarget(p);
			h.assertTrue(husk.getTarget() == null, "setTarget(player) is refused while fleeing");
			h.assertTrue(outside.canAttack(p), "outside the 4 block radius the fear does not apply");
			h.assertTrue(creeper.canAttack(p), "creepers are not in #fears_golem");

			husk.setOnGround(true); // spawned this tick on the stone floor: navigation only paths from the ground
			boolean fled = false;
			for (int attempt = 0; attempt < 20 && !fled; attempt++) {
				tick(p, MobAttitudeBehavior.TYPE);
				Path path = husk.getNavigation().getPath();
				fled = path != null && path.getTarget() != null
						&& Vec3.atBottomCenterOf(path.getTarget()).distanceToSqr(p.position()) > husk.distanceToSqr(p);
			}
			h.assertTrue(fled, "the husk paths away from the player");

			// hurting a feared mob breaks the fear of all of them for pause_on_hit ticks
			husk.hurtServer(h.getLevel(), p.damageSources().playerAttack(p), 1.0F);
			h.assertTrue(other.canAttack(p), "after a hit the other husk may fight back");
			other.getNavigation().stop();
			tick(p, MobAttitudeBehavior.TYPE);
			h.assertTrue(other.getNavigation().isDone(), "paused: no fleeing");

			// hard exclusions: through a word or tag, creepers / ravagers never flee; a creeper listed by id does (cat)
			SourceDefinition everyone = custom("flee_all", MobAttitudeBehavior.TYPE,
					"{\"entities\": [\"all_mobs\", \"minecraft:ravager\"], \"attitude\": \"flee\", \"radius\": [8]}", false);
			Mob ravager = h.spawn(EntityTypes.RAVAGER, new Vec3(5.5, 1.0, 1.5));
			give(cat, 1, 0, everyone);
			h.assertTrue(creeper.canAttack(cat), "a creeper matched through a word never flees");
			h.assertTrue(ravager.canAttack(cat), "a ravager never flees, even when listed");
			h.assertFalse(husk.canAttack(cat), "other mobs matched by the word do");
			give(cat, 1, 0, shipped(h, "cat")); // creeper, phantom by id
			h.assertFalse(creeper.canAttack(cat), "cat trait: a creeper listed by id flees");
		} finally {
			remove(p);
			remove(cat);
		}
		h.succeed();
	}

	@GameTest
	public void mobAttitudeIgnoreUntilRevenge(GameTestHelper h) {
		ServerPlayer p = player(h);
		ServerPlayer q = player(h);
		try {
			Mob first = h.spawn(EntityTypes.ENDERMAN, new Vec3(3.5, 1.0, 1.5));
			Mob second = h.spawn(EntityTypes.ENDERMAN, new Vec3(5.5, 1.0, 5.5));
			Mob zombie = h.spawn(EntityTypes.ZOMBIE, new Vec3(1.5, 1.0, 5.5));
			give(p, 1, 0, shipped(h, "pumpkin")); // endermen ignore the player
			first.setTarget(p);
			h.assertTrue(first.getTarget() == null, "an enderman never targets a pumpkin-absorber on its own");
			h.assertFalse(second.canAttack(p), "not even through its stare goal (canAttack refused)");
			h.assertTrue(zombie.canAttack(p), "other mobs are unaffected");

			first.hurtServer(h.getLevel(), p.damageSources().playerAttack(p), 1.0F);
			h.assertTrue(first.canAttack(p), "revenge: the hurt enderman may attack");
			h.assertTrue(second.canAttack(p), "revenge: so may one of the same type within 16 blocks");

			Mob piglin = h.spawn(EntityTypes.PIGLIN, new Vec3(6.5, 1.0, 1.5));
			h.assertTrue(piglin.canAttack(q), "a piglin attacks a player without gold");
			give(q, 1, 0, shipped(h, "piglin")); // piglins ignore the player (brain mob: StartAttacking checks canAttack)
			h.assertFalse(piglin.canAttack(q), "piglin trait: piglins ignore the player");
		} finally {
			remove(p);
			remove(q);
		}
		h.succeed();
	}

	// ---- detection_range -----------------------------------------------------------------------------------------

	@GameTest
	public void detectionRangeHidesOrExposesThePlayer(GameTestHelper h) {
		ServerPlayer p = player(h);
		ServerPlayer q = player(h);
		try {
			Mob zombie = h.spawn(EntityTypes.ZOMBIE, new Vec3(1.5, 1.0, 6.5));
			Mob cow = h.spawn(EntityTypes.COW, new Vec3(6.5, 1.0, 6.5));
			give(p, 3, 0, shipped(h, "pumpkin")); // ×0.7 for every hostile looker
			near(h, TraitEngine.visibilityFactor(p, zombie), 0.7, "pumpkin III: hostile mobs see the player at 70 %");
			near(h, TraitEngine.visibilityFactor(p, cow), 1.0, "a cow is not a hostile looker");
			give(p, 3, 0, shipped(h, "grass")); // ×0.55 while sneaking
			near(h, TraitEngine.visibilityFactor(p, zombie), 1.0, "grass: not sneaking, no change");
			p.setShiftKeyDown(true);
			refresh(p, shipped(h, "grass"));
			near(h, TraitEngine.visibilityFactor(p, zombie), 0.55, "grass III while sneaking: 55 %");

			// amethyst weakness III: ×1.7 → hostile mobs notice the player beyond their follow range
			Mob seen = h.spawn(EntityTypes.HUSK, new Vec3(7.5, 1.0, 1.5)); // 6 blocks from q
			Mob tooFar = h.spawn(EntityTypes.HUSK, new Vec3(7.5, 1.0, 6.5)); // 7.8 blocks
			for (Mob husk : List.of(seen, tooFar)) {
				husk.getAttribute(Attributes.FOLLOW_RANGE).removeModifier(Identifier.withDefaultNamespace("random_spawn_bonus"));
				husk.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(4.0);
			}
			near(h, seen.getAttributeValue(Attributes.FOLLOW_RANGE), 4.0, "test setup: follow range");
			give(q, 0, 3, shipped(h, "amethyst"));
			near(h, TraitEngine.visibilityFactor(q, seen), 1.7, "amethyst III: ×1.7");
			tick(q, DetectionRangeBehavior.TYPE);
			h.assertTrue(seen.getTarget() == q, "a husk 6 blocks away (follow range 4 × 1.7) notices the player");
			h.assertTrue(tooFar.getTarget() == null, "one 7.8 blocks away does not");
			give(q, 0, 0);
			h.assertTrue(seen.getTarget() == null, "the provoked husk calms down when the weakness is gone");
		} finally {
			remove(p);
			remove(q);
		}
		h.succeed();
	}

	// ---- item_magnet ---------------------------------------------------------------------------------------------

	@GameTest
	public void itemMagnetPullsPickableItems(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			ServerLevel level = h.getLevel();
			Vec3 at = h.absoluteVec(new Vec3(4.5, 1.0, 1.5));
			ItemEntity free = new ItemEntity(level, at.x, at.y, at.z, new ItemStack(Items.DIAMOND));
			ItemEntity delayed = new ItemEntity(level, at.x, at.y, at.z + 1.0, new ItemStack(Items.DIAMOND));
			ItemEntity thrown = new ItemEntity(level, at.x, at.y, at.z + 2.0, new ItemStack(Items.DIAMOND));
			Vec3 far = h.absoluteVec(new Vec3(6.5, 1.0, 6.5));
			ItemEntity outside = new ItemEntity(level, far.x, far.y, far.z, new ItemStack(Items.DIAMOND));
			free.setNoPickUpDelay();
			delayed.setPickUpDelay(40);
			thrown.setNoPickUpDelay();
			thrown.setThrower(p);
			outside.setNoPickUpDelay();
			for (ItemEntity e : List.of(free, delayed, thrown, outside)) {
				level.addFreshEntity(e);
				e.setDeltaMovement(Vec3.ZERO);
			}
			give(p, 1, 0, shipped(h, "allay")); // radius 4
			tick(p, ItemMagnetBehavior.TYPE);
			Vec3 pull = free.getDeltaMovement();
			Vec3 toPlayer = p.position().add(0.0, p.getBbHeight() * 0.5, 0.0).subtract(free.position()).normalize();
			near(h, pull.length(), 0.25, "pulled at 0.25 blocks per tick");
			h.assertTrue(pull.normalize().dot(toPlayer) > 0.999, "toward the player");
			h.assertTrue(delayed.getDeltaMovement().equals(Vec3.ZERO), "an item with a pickup delay stays");
			h.assertTrue(thrown.getDeltaMovement().equals(Vec3.ZERO), "an item the player just threw stays");
			h.assertTrue(outside.getDeltaMovement().equals(Vec3.ZERO), "an item beyond radius 4 stays");
			for (ItemEntity e : List.of(free, delayed, thrown, outside)) e.discard();
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- helpers -------------------------------------------------------------------------------------------------

	private static int countMine(SourceSpec.SideSpec side) {
		int n = 0;
		for (SourceSpec.BehaviorSpec b : side.behaviors()) {
			Identifier type = Identifier.tryParse(b.type());
			if (type != null && TYPES.contains(type)) n++;
		}
		return n;
	}

	/** The side with only this package's behavior entries ({@code attributes}: keep the side's attributes too). */
	private static SourceSpec.SideSpec keepMine(SourceSpec.SideSpec side, boolean attributes) {
		List<SourceSpec.BehaviorSpec> mine = new ArrayList<>();
		for (SourceSpec.BehaviorSpec b : side.behaviors()) {
			Identifier type = Identifier.tryParse(b.type());
			if (type != null && TYPES.contains(type)) mine.add(b);
		}
		return new SourceSpec.SideSpec(side.key(), attributes ? side.attributes() : List.of(), mine);
	}

	/**
	 * The shipped source {@code absorbaholic:<name>} resolved with only this package's behavior entries (no attributes,
	 * no other packages' behaviors), under a test id and with no targets.
	 */
	private static SourceDefinition shipped(GameTestHelper h, String name) {
		Identifier id = Absorbaholic.id(name);
		SourceLoader.RawSource raw = SourceLoader.read(h.getLevel().getServer().getResourceManager()).stream()
				.filter(f -> f.id().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("no shipped source " + id));
		if (!(raw.parsed() instanceof SourceSpecParser.Result.Parsed(SourceSpec spec))) throw new IllegalStateException(id + " does not parse");
		SourceSpec onlyMine = new SourceSpec(spec.kind(), spec.targets(), spec.name(), spec.icon(), spec.color(), spec.tier(), spec.maxLevel(),
				keepMine(spec.trait(), false), keepMine(spec.weakness(), false));
		List<String> errors = new ArrayList<>();
		SourceDefinition d = SourceResolver.resolve(id, onlyMine, errors::add);
		if (d == null) throw new IllegalStateException(id + ": " + errors);
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", "behb/" + name),
				new SourceTargets(d.targets().kind(), List.of(), List.of()), d.nameKey(), Optional.empty(), d.color(), d.tier(), d.maxLevel(),
				d.trait(), d.weakness());
	}

	/** A test source with one behavior entry decoded by the type's real codec from {@code json}. */
	private static <P> SourceDefinition custom(String name, BehaviorType<P> type, String json, boolean weakness) {
		P params = type.codec().codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
		List<BehaviorEntry<?>> entries = List.of(new BehaviorEntry<>(type, params));
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", "behb/custom/" + name),
				new SourceTargets(SourceKind.BLOCK, List.of(), List.of()), Optional.empty(), 0xFFFFFF, Tier.COMMON, 3,
				new SourceDefinition.Side("test_trait", List.of(), weakness ? List.of() : entries),
				new SourceDefinition.Side("test_weakness", List.of(), weakness ? entries : List.of()));
	}

	/** Runs the tick hook of every active entry of {@code type} once (the engine schedules the same call). */
	private static void tick(ServerPlayer p, BehaviorType<?> type) {
		for (ActiveBehavior<?> a : TraitEngine.active(p).forHook(Hook.TICK)) {
			if (a.type() == type) a.tick(p);
		}
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

	private static void near(GameTestHelper h, double actual, double expected, String message) {
		h.assertTrue(Math.abs(actual - expected) <= EPS, message + ": expected " + expected + ", got " + actual);
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

	/** Gives the player {@code sources} at the given levels (none = no traits) and rebuilds synchronously. */
	private static void give(ServerPlayer p, int traitLevel, int weaknessLevel, SourceDefinition... sources) {
		withSources(() -> {
			PlayerTraits traits = PlayerTraits.EMPTY;
			if (traitLevel > 0 || weaknessLevel > 0) {
				for (SourceDefinition s : sources) traits = traits.with(new TraitEntry(s.id(), traitLevel, weaknessLevel, false, false));
			}
			PlayerData.setTraits(p, traits);
			TraitEngine.recompute(p);
		}, sources);
	}

	/** Rebuilds the active set with the same traits (re-evaluates cached conditions). */
	private static void refresh(ServerPlayer p, SourceDefinition... sources) {
		withSources(() -> TraitEngine.recompute(p), sources);
	}

	/** Re-applies the test sources whenever another test's mode toggle made the engine rebuild without them. */
	private static void keep(GameTestHelper h, ServerPlayer p, int traitLevel, int weaknessLevel, SourceDefinition... sources) {
		Set<Identifier> ids = new LinkedHashSet<>();
		for (SourceDefinition s : sources) ids.add(s.id());
		h.onEachTick(() -> {
			if (!p.isAlive() || p.hasDisconnected()) return;
			boolean missing = TraitEngine.active(p).all().stream().noneMatch(a -> ids.contains(a.sourceId()));
			if (missing || PlayerData.runtime(p).dirty) give(p, traitLevel, weaknessLevel, sources);
		});
	}
}
