package dev.absorbaholic.test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.absorbaholic.core.AbsorbCaps;
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
import dev.absorbaholic.trait.MovementFlags;
import dev.absorbaholic.trait.MovementFlagsHolder;
import dev.absorbaholic.trait.MovementState;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.trait.WeaknessDamage;
import dev.absorbaholic.trait.behavior.AbilitySupport;
import dev.absorbaholic.trait.behavior.AirJumpBehavior;
import dev.absorbaholic.trait.behavior.ClimbWallsBehavior;
import dev.absorbaholic.trait.behavior.FlightBehavior;
import dev.absorbaholic.trait.behavior.GlideBehavior;
import dev.absorbaholic.trait.behavior.ShootProjectileBehavior;
import dev.absorbaholic.trait.behavior.SinkInWaterBehavior;
import dev.absorbaholic.trait.behavior.SneakDetonateBehavior;
import dev.absorbaholic.trait.behavior.SonicBoomBehavior;
import dev.absorbaholic.trait.behavior.TeleportBehavior;
import dev.absorbaholic.trait.behavior.WalkOnFluidBehavior;
import net.fabricmc.fabric.api.entity.event.v1.EntityElytraEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * WP-BEH-C server gametests: one or more per movement / ability behavior type, each proving the effect with concrete
 * numbers through the real engine wiring: sources are given with {@code PlayerData.setTraits} +
 * {@code TraitEngine.recompute}, triggers go through {@code TraitEngine.fireTrigger} (exhaustion, one ability per
 * trigger), damage goes through the real pipeline (AFTER_DAMAGE / ALLOW_DAMAGE), and periodic logic runs the engine-built
 * active entries' {@code tick}. Timers live in {@code PlayerRuntime.abilityCooldowns} under
 * {@link AbilitySupport#key}; tests expire them instead of waiting, so everything stays synchronous (the registry is
 * shared by parallel tests) except the projectile flights. Also checks that every shipped source using these types
 * resolves.
 */
public class BehaviorCGameTests {
	private static final double EPS = 1.0E-6;
	private static final List<BehaviorType<?>> TYPES = List.of(WalkOnFluidBehavior.TYPE, ClimbWallsBehavior.TYPE, SinkInWaterBehavior.TYPE,
			AirJumpBehavior.TYPE, GlideBehavior.TYPE, FlightBehavior.TYPE, TeleportBehavior.TYPE, SneakDetonateBehavior.TYPE,
			ShootProjectileBehavior.TYPE, SonicBoomBehavior.TYPE);

	// ---- data ------------------------------------------------------------------------------------------------

	/** Every shipped behavior entry of our types decodes (no unknown params, arrays cover max_level). */
	@GameTest
	public void shippedSourcesUsingBehaviorCTypesResolve(GameTestHelper h) {
		Set<Identifier> ours = new java.util.HashSet<>();
		for (BehaviorType<?> t : TYPES) ours.add(t.id());
		Map<Identifier, Integer> seen = new HashMap<>();
		List<String> problems = new ArrayList<>();
		for (SourceLoader.RawSource file : SourceLoader.read(h.getLevel().getServer().getResourceManager())) {
			if (!file.id().getNamespace().equals("absorbaholic") || !(file.parsed() instanceof SourceSpecParser.Result.Parsed(SourceSpec spec))) continue;
			SourceSpec.SideSpec trait = only(spec.trait(), ours, seen);
			SourceSpec.SideSpec weakness = only(spec.weakness(), ours, seen);
			if (trait.behaviors().isEmpty() && weakness.behaviors().isEmpty()) continue;
			SourceSpec reduced = new SourceSpec(spec.kind(), spec.targets(), spec.name(), spec.icon(), spec.color(), spec.tier(), spec.maxLevel(), trait, weakness);
			List<String> errors = SourceResolver.errorsOf(file.id(), reduced);
			List<String> params = SourceResolver.warningsOf(file.id(), reduced).stream().filter(w -> w.contains("param")).toList();
			if (!errors.isEmpty()) problems.add(file.id() + ": " + errors);
			if (!params.isEmpty()) problems.add(file.id() + ": " + params);
		}
		h.assertTrue(problems.isEmpty(), "shipped sources: " + problems);
		for (Identifier id : ours) h.assertTrue(seen.getOrDefault(id, 0) >= 1, "shipped data uses " + id);
		h.assertTrue(seen.values().stream().mapToInt(Integer::intValue).sum() >= 22, "22 shipped entries of our types: " + seen);
		h.succeed();
	}

	private static SourceSpec.SideSpec only(SourceSpec.SideSpec side, Set<Identifier> ours, Map<Identifier, Integer> seen) {
		List<SourceSpec.BehaviorSpec> mine = new ArrayList<>();
		for (SourceSpec.BehaviorSpec b : side.behaviors()) {
			Identifier type = Identifier.tryParse(b.type());
			if (type != null && ours.contains(type)) {
				mine.add(b);
				seen.merge(type, 1, Integer::sum);
			}
		}
		return new SourceSpec.SideSpec(side.key(), List.of(), mine);
	}

	@GameTest
	public void invalidParamsAreRejected(GameTestHelper h) {
		rejects(h, WalkOnFluidBehavior.TYPE, "{\"fluid\":\"lava\",\"mode\":\"frost\",\"radius\":[2]}", "frost on lava");
		rejects(h, WalkOnFluidBehavior.TYPE, "{\"fluid\":\"lava\",\"mode\":\"solid\",\"condition\":\"in_nether\"}", "solid with a condition");
		rejects(h, ClimbWallsBehavior.TYPE, "{\"speed\":[0.1],\"condition\":\"night\"}", "climb with a condition");
		rejects(h, TeleportBehavior.TYPE, "{\"trigger\":\"on_hurt\",\"mode\":\"random\",\"range\":[4],\"cooldown\":[60]}", "on_hurt without chance");
		rejects(h, TeleportBehavior.TYPE, "{\"trigger\":\"sneak_jump\",\"mode\":\"sideways\",\"range\":[4],\"cooldown\":[60]}", "unknown mode");
		rejects(h, ShootProjectileBehavior.TYPE, "{\"projectile\":\"small_fireball\",\"damage\":[3],\"cooldown\":[40]}", "damage on a fireball");
		rejects(h, ShootProjectileBehavior.TYPE, "{\"projectile\":\"snowball\",\"explosion_power\":[1],\"cooldown\":[40]}", "power on a snowball");
		rejects(h, ShootProjectileBehavior.TYPE, "{\"projectile\":\"evoker_fangs\",\"damage\":[20],\"cooldown\":[40]}", "fang damage out of 3..12");
		rejects(h, ShootProjectileBehavior.TYPE, "{\"projectile\":\"potato\",\"cooldown\":[40]}", "unknown projectile");
		rejects(h, SneakDetonateBehavior.TYPE, "{\"power\":[2],\"fuse\":-1,\"cooldown\":[600]}", "negative fuse");
		h.succeed();
	}

	// ---- walk_on_fluid ---------------------------------------------------------------------------------------

	@GameTest
	public void walkOnFluidFrostFreezesStillWater(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, 1, z), x == 0 || z == 0 || x == 7 || z == 7 ? Blocks.STONE : Blocks.WATER);
		}
		h.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
		ServerPlayer p = player(h, 3.5, 2, 3.5, 0, 0);
		try {
			give(p, 2, 0, source("frost", List.of(entry(WalkOnFluidBehavior.TYPE, "{\"fluid\":\"water\",\"mode\":\"frost\",\"radius\":[2,3,4]}")), List.of()));
			h.assertTrue(((MovementFlagsHolder) p).absorbaholic$movement() == MovementState.NONE, "frost mode sets no client flag");
			p.setOnGround(true);
			p.setShiftKeyDown(true);
			tick(p, WalkOnFluidBehavior.TYPE);
			h.assertBlockPresent(Blocks.WATER, new BlockPos(5, 1, 3));
			p.setShiftKeyDown(false);
			tick(p, WalkOnFluidBehavior.TYPE);
			// level II: radius 3 around the block below the feet, measured from the player's x/z (vanilla ReplaceDisk)
			for (BlockPos frozen : List.of(new BlockPos(5, 1, 3), new BlockPos(1, 1, 3), new BlockPos(3, 1, 5), new BlockPos(5, 1, 5), new BlockPos(2, 1, 2))) {
				h.assertBlockPresent(Blocks.FROSTED_ICE, frozen);
			}
			for (BlockPos water : List.of(new BlockPos(6, 1, 3), new BlockPos(3, 1, 6), new BlockPos(6, 1, 6), new BlockPos(1, 1, 6))) {
				h.assertBlockPresent(Blocks.WATER, water);
			}
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void walkOnFluidSolidStandsOnLava(GameTestHelper h) {
		ServerPlayer p = player(h, 1.5, 1, 1.5, 0, 0);
		try {
			give(p, 1, 0, source("strider", List.of(entry(WalkOnFluidBehavior.TYPE, "{\"fluid\":\"lava\",\"mode\":\"solid\",\"radius\":[0,0,0]}")), List.of()));
			MovementState m = ((MovementFlagsHolder) p).absorbaholic$movement();
			h.assertTrue(m.has(MovementFlags.WALK_ON_LAVA) && !m.has(MovementFlags.WALK_ON_WATER), "lava flag only: " + m);
			h.assertTrue(p.canStandOnFluid(Fluids.LAVA.defaultFluidState()), "stands on lava");
			h.assertFalse(p.canStandOnFluid(Fluids.WATER.defaultFluidState()), "not on water");
			p.setShiftKeyDown(true);
			h.assertFalse(p.canStandOnFluid(Fluids.LAVA.defaultFluidState()), "sneaking sinks");
			p.setShiftKeyDown(false);
			PlayerData.setTraits(p, PlayerTraits.EMPTY);
			TraitEngine.recompute(p);
			h.assertFalse(p.canStandOnFluid(Fluids.LAVA.defaultFluidState()), "trait removed → vanilla");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- climb_walls -----------------------------------------------------------------------------------------

	@GameTest
	public void climbWallsMakesWallsClimbable(GameTestHelper h) {
		ServerPlayer p = player(h, 1.5, 1, 1.5, 0, 0);
		try {
			give(p, 2, 0, source("spider", List.of(entry(ClimbWallsBehavior.TYPE, "{\"speed\":[0.12,0.16,0.2]}")), List.of()));
			MovementState m = ((MovementFlagsHolder) p).absorbaholic$movement();
			h.assertTrue(m.has(MovementFlags.CLIMB_WALLS), "climb flag");
			near(h, m.climbSpeed(), 0.16, 1.0E-6, "level II climb speed");
			p.horizontalCollision = true;
			h.assertTrue(p.onClimbable(), "pushing against a wall = on a ladder");
			p.horizontalCollision = false;
			h.assertFalse(p.onClimbable(), "no wall, no ladder");
			give(p, 1, 0, source("spider_fast", List.of(entry(ClimbWallsBehavior.TYPE, "{\"speed\":[9.0]}")), List.of()));
			near(h, ((MovementFlagsHolder) p).absorbaholic$movement().climbSpeed(), AbsorbCaps.ABILITY_MAX_VELOCITY, 1.0E-6, "climb speed capped");
			PlayerData.setTraits(p, PlayerTraits.EMPTY);
			TraitEngine.recompute(p);
			p.horizontalCollision = true;
			h.assertFalse(p.onClimbable(), "trait removed → walls are walls");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- sink_in_water ---------------------------------------------------------------------------------------

	@GameTest
	public void sinkInWaterPullsVanillaClientsDown(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		for (int y = 1; y <= 3; y++) {
			for (int x = 0; x < 8; x++) {
				for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, y, z), x == 0 || z == 0 || x == 7 || z == 7 || y == 3 ? Blocks.STONE : Blocks.WATER);
			}
		}
		h.setBlock(new BlockPos(3, 3, 3), Blocks.AIR);
		ServerPlayer p = player(h, 3.5, 1.2, 3.5, 0, 0);
		try {
			give(p, 1, 0, source("iron", List.of(entry(SinkInWaterBehavior.TYPE, "{\"speed\":[0.05]}")), List.of()));
			MovementState m = ((MovementFlagsHolder) p).absorbaholic$movement();
			h.assertTrue(m.has(MovementFlags.SINK_IN_WATER), "sink flag for modded clients");
			near(h, m.sinkSpeed(), 0.05, 1.0E-6, "synced sink speed");
			p.doTick(); // updates isInWater
			h.assertTrue(p.isInWater(), "standing in water");

			// the mock player cannot receive our payload (a vanilla client): the server pushes, two ticks' worth
			p.setKnownMovement(new Vec3(0.1, 0.2, 0.0));
			tick(p, SinkInWaterBehavior.TYPE);
			near(h, p.getDeltaMovement().y, -0.1, EPS, "no swimming up, then 2 × 0.05 down");
			near(h, p.getDeltaMovement().x, 0.1, EPS, "horizontal movement kept");
			p.setKnownMovement(new Vec3(0.0, -0.25, 0.0));
			tick(p, SinkInWaterBehavior.TYPE);
			near(h, p.getDeltaMovement().y, AbsorbCaps.SINK_MAX_FALL_VELOCITY, EPS, "terminal velocity -0.3");
			p.setDeltaMovement(Vec3.ZERO);
			p.setKnownMovement(new Vec3(0.0, -0.5, 0.0));
			tick(p, SinkInWaterBehavior.TYPE);
			near(h, p.getDeltaMovement().y, 0.0, EPS, "faster than terminal: left alone");
			near(h, SinkInWaterBehavior.sink(0.2, 0.1, true), 0.1, EPS, "against a wall the player may climb out");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- air_jump --------------------------------------------------------------------------------------------

	@GameTest
	public void airJumpBurstsUseChargesAndCooldown(GameTestHelper h) throws ReflectiveOperationException {
		ServerPlayer p = player(h, 3.5, 4, 3.5, 0, 0);
		try {
			give(p, 2, 0, source("bee", List.of(entry(AirJumpBehavior.TYPE, "{\"charges\":[1,2,3],\"velocity\":[0.5,0.55,0.6],\"cooldown\":8}")), List.of()));
			ActiveBehavior<?> a = active(p, AirJumpBehavior.TYPE);
			p.setOnGround(false);
			p.setKnownMovement(new Vec3(0.2, -0.4, 0.0));
			p.fallDistance = 6.0;
			float exhaustion = exhaustion(p);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "first burst");
			near(h, p.getDeltaMovement().y, 0.55, EPS, "level II velocity");
			near(h, p.getDeltaMovement().x, 0.2, EPS, "horizontal kept");
			near(h, p.fallDistance, 6.0 - 0.55 * 0.55 / 0.16, EPS, "fall distance lowered by the burst's height only");
			near(h, exhaustion(p) - exhaustion, AbsorbCaps.AIR_JUMP_EXHAUSTION, 1.0E-4, "air jump costs 0.5 exhaustion");
			h.assertValueEqual(timer(p, AbilitySupport.key(a)), now(h) + 8, "8 ticks between bursts");
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "burst cooldown");
			expire(p, AbilitySupport.key(a));
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "second charge");
			expire(p, AbilitySupport.key(a));
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "level II: two charges per airtime");
			p.setOnGround(true);
			tick(p, AirJumpBehavior.TYPE);
			p.setOnGround(false);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "charges refill on the ground");

			give(p, 1, 0, source("bee_fast", List.of(entry(AirJumpBehavior.TYPE, "{\"charges\":[1],\"velocity\":[5.0]}")), List.of()));
			p.setKnownMovement(Vec3.ZERO);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "fast bee");
			near(h, p.getDeltaMovement().y, AbsorbCaps.ABILITY_MAX_VELOCITY, EPS, "velocity capped at 1.6");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	/** Review m4: one charge just above the ground must not turn a 200-block fall into a safe landing. */
	@GameTest
	public void airJumpDoesNotCancelALongFall(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		ServerPlayer p = player(h, 3.5, 2, 3.5, 0, 0);
		try {
			give(p, 1, 0, source("bee_long_fall", List.of(entry(AirJumpBehavior.TYPE, "{\"charges\":[1],\"velocity\":[0.5],\"cooldown\":0}")), List.of()));
			p.setOnGround(false);
			p.setKnownMovement(new Vec3(0.0, -3.0, 0.0));
			p.fallDistance = 200.0;
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "burst 1 block above the ground");
			near(h, p.getDeltaMovement().y, 0.5, EPS, "burst velocity");
			near(h, AirJumpBehavior.fallCredit(0.5), 0.5 * 0.5 / 0.16, EPS, "credit v²/2g");
			near(h, p.fallDistance, 200.0 - AirJumpBehavior.fallCredit(0.5), EPS, "only the burst's height is taken off");
			h.assertTrue(p.fallDistance > 190.0, "a single charge leaves a long fall lethal: " + p.fallDistance);

			// a small hop: the credit never makes the fall distance negative
			p.setOnGround(true);
			tick(p, AirJumpBehavior.TYPE);
			p.setOnGround(false);
			p.fallDistance = 0.5;
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "second airtime");
			near(h, p.fallDistance, 0.0, EPS, "clamped at 0");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- glide -----------------------------------------------------------------------------------------------

	@GameTest
	public void glideRunsForMaxTicksOncePerAirtime(GameTestHelper h) {
		ServerPlayer p = player(h, 3.5, 4, 3.5, 0, 0);
		try {
			give(p, 1, 0, source("phantom", List.of(entry(GlideBehavior.TYPE, "{\"max_ticks\":[100,200,400]}")), List.of()));
			h.assertTrue(((MovementFlagsHolder) p).absorbaholic$movement().has(MovementFlags.GLIDE), "glide flag");
			h.assertTrue(EntityElytraEvents.CUSTOM.invoker().useCustomElytra(p, false), "the flag is a custom elytra");
			p.setOnGround(false);
			h.assertFalse(EntityElytraEvents.ALLOW.invoker().allowElytraFlight(p), "no elytra-less glide before the trigger");
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "air_jump trigger starts the glide");
			h.assertTrue(p.isFallFlying(), "fall flying");
			h.assertTrue(EntityElytraEvents.ALLOW.invoker().allowElytraFlight(p), "our glide is allowed");
			for (int i = 1; i < 100; i++) tick(p, GlideBehavior.TYPE);
			h.assertTrue(p.isFallFlying(), "still gliding after 99 ticks");
			tick(p, GlideBehavior.TYPE);
			h.assertFalse(p.isFallFlying(), "level I: stops after 100 ticks");
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "no second glide before touching the ground");
			h.assertFalse(EntityElytraEvents.ALLOW.invoker().allowElytraFlight(p), "a client-started glide is refused");
			h.assertFalse(p.tryToStartFallFlying(), "START_FALL_FLYING from the client fails");
			p.setOnGround(true);
			tick(p, GlideBehavior.TYPE);
			p.setOnGround(false);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.AIR_JUMP, null), "ground touched: glide again");
			PlayerData.setTraits(p, PlayerTraits.EMPTY);
			TraitEngine.recompute(p);
			h.assertFalse(p.isFallFlying(), "losing the trait ends the glide");
		} finally {
			remove(p);
		}

		// with air_jump too: bursts first (even though glide was acquired first), glide once charges are spent
		ServerPlayer both = player(h, 3.5, 4, 3.5, 0, 0);
		try {
			SourceDefinition phantom = source("phantom_first", List.of(entry(GlideBehavior.TYPE, "{\"max_ticks\":[100]}")), List.of());
			SourceDefinition bee = source("bee_second", List.of(entry(AirJumpBehavior.TYPE, "{\"charges\":[1],\"velocity\":[0.5],\"cooldown\":8}")), List.of());
			give(both, 1, 0, phantom, bee);
			both.setOnGround(false);
			both.setKnownMovement(Vec3.ZERO);
			h.assertTrue(TraitEngine.fireTrigger(both, Hook.AIR_JUMP, null), "first press");
			h.assertFalse(both.isFallFlying(), "air jump used its charge instead of gliding");
			near(h, both.getDeltaMovement().y, 0.5, EPS, "burst");
			expire(both, AbilitySupport.key(active(both, AirJumpBehavior.TYPE)));
			h.assertTrue(TraitEngine.fireTrigger(both, Hook.AIR_JUMP, null), "second press");
			h.assertTrue(both.isFallFlying(), "no charges left: glide takes over");
		} finally {
			remove(both);
		}
		h.succeed();
	}

	// ---- flight ----------------------------------------------------------------------------------------------

	@GameTest
	public void flightGrantsLocksInCombatAndRestores(GameTestHelper h) throws ReflectiveOperationException {
		ServerPlayer p = player(h, 3.5, 3, 3.5, 0, 0);
		SourceDefinition egg = source("egg", List.of(entry(FlightBehavior.TYPE, "{\"speed\":[0.08],\"combat_lock_ticks\":[100]}")), List.of());
		try {
			give(p, 1, 0, egg);
			Abilities a = p.getAbilities();
			h.assertTrue(a.mayfly, "mayfly granted");
			near(h, a.getFlyingSpeed(), 0.08, 1.0E-6, "flying speed");

			a.flying = true;
			float before = exhaustion(p);
			tick(p, FlightBehavior.TYPE);
			near(h, exhaustion(p) - before, AbsorbCaps.FLIGHT_EXHAUSTION_PER_TICK, 1.0E-4, "flying costs 0.01 exhaustion per tick");

			// hit by a projectile (real damage pipeline → AFTER_DAMAGE → onAttacked)
			Arrow arrow = new Arrow(EntityTypes.ARROW, h.getLevel());
			p.hurtServer(h.getLevel(), p.damageSources().arrow(arrow, null), 1.0F);
			h.assertFalse(a.mayfly || a.flying, "combat lock: flight off, the player falls");
			ActiveBehavior<?> flight = active(p, FlightBehavior.TYPE);
			h.assertValueEqual(timer(p, FlightBehavior.lockKey(flight)), now(h) + 100, "locked for 100 ticks");
			tick(p, FlightBehavior.TYPE);
			h.assertFalse(a.mayfly, "still locked");
			expire(p, FlightBehavior.lockKey(flight));
			p.setOnGround(false);
			tick(p, FlightBehavior.TYPE);
			h.assertFalse(a.mayfly, "lock over mid-air: no flight (and so no fall-damage immunity) until landing");
			p.setOnGround(true);
			tick(p, FlightBehavior.TYPE);
			h.assertTrue(a.mayfly, "lock over and landed: flight back");

			// mode OFF mid-air while flying: flight removed, slow falling so it is not a death sentence
			a.flying = true;
			p.setOnGround(false);
			TestSupport.setMode(h, false);
			try {
				TraitEngine.recompute(p);
				h.assertFalse(a.mayfly || a.flying, "mode OFF clears flight");
				near(h, a.getFlyingSpeed(), 0.05, 1.0E-6, "flying speed restored");
				MobEffectInstance slow = p.getEffect(MobEffects.SLOW_FALLING);
				h.assertTrue(slow != null && slow.getDuration() == AbsorbCaps.FLIGHT_LOSS_SLOW_FALLING_TICKS, "200 ticks of slow falling: " + slow);
			} finally {
				TestSupport.setMode(h, true);
			}
			p.removeAllEffects();
			give(p, 1, 0, egg);
			h.assertTrue(a.mayfly, "mode ON: flight again");

			// creative: the engine deactivates the entry but never touches creative abilities
			p.setGameMode(GameType.CREATIVE);
			a.flying = true;
			TraitEngine.recompute(p);
			h.assertTrue(a.mayfly && a.flying, "creative flight untouched");
			h.assertTrue(p.getEffect(MobEffects.SLOW_FALLING) == null, "no slow falling in creative");
			p.setGameMode(GameType.SURVIVAL);
			h.assertFalse(a.mayfly, "vanilla survival resets mayfly");
			give(p, 1, 0, egg); // recompute with the test source registered
			h.assertTrue(a.mayfly, "back in survival: granted again");

			// trait removal on the ground: no slow falling
			p.setOnGround(true);
			PlayerData.setTraits(p, PlayerTraits.EMPTY);
			TraitEngine.recompute(p);
			h.assertFalse(a.mayfly || a.flying, "trait removed: no flight");
			h.assertTrue(p.getEffect(MobEffects.SLOW_FALLING) == null, "grounded: no slow falling");
		} finally {
			TestSupport.setMode(h, true);
			remove(p);
		}
		h.succeed();
	}

	// ---- teleport --------------------------------------------------------------------------------------------

	@GameTest
	public void teleportLookLandsOnSafeGroundWithCooldown(GameTestHelper h) throws ReflectiveOperationException {
		for (int x = 0; x < 8; x++) h.setBlock(new BlockPos(x, 0, 3), Blocks.STONE);
		for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(6, y, 3), Blocks.STONE);
		ServerPlayer p = player(h, 0.5, 1, 3.5, 0, 0);
		ahead(h, p, 0.5, 1, 3.5);
		try {
			give(p, 1, 0, source("enderman", List.of(entry(TeleportBehavior.TYPE,
					"{\"trigger\":\"sneak_jump\",\"mode\":\"look\",\"range\":[8,12,16],\"cooldown\":[100,80,60]}")), List.of()));
			ActiveBehavior<?> t = active(p, TeleportBehavior.TYPE);
			float exhaustion = exhaustion(p);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_JUMP, null), "teleported");
			Vec3 expected = h.absoluteVec(new Vec3(5.5, 1.0, 3.5));
			h.assertTrue(p.position().distanceTo(expected) < 1.0E-6, "last safe spot before the wall: " + p.position() + " vs " + expected);
			near(h, exhaustion(p) - exhaustion, AbsorbCaps.ABILITY_EXHAUSTION, 1.0E-4, "an ability costs 1.0 exhaustion");
			h.assertValueEqual(timer(p, AbilitySupport.key(t)), now(h) + 100, "level I cooldown 100");
			ahead(h, p, 0.5, 1, 3.5);
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.SNEAK_JUMP, null), "on cooldown");

			// no ground anywhere along the ray (looking out over the void side): fails, no cooldown
			expire(p, AbilitySupport.key(t));
			PlayerData.runtime(p).abilityCooldowns.remove(AbilitySupport.key(t));
			Vec3 edge = h.absoluteVec(new Vec3(0.5, 1, 3.5));
			look(h, p, new Vec3(0.5, 1, 3.5), new Vec3(0.5, 12, 7.5)); // steeply up over the side without floor
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.SNEAK_JUMP, null), "no safe spot → no teleport");
			h.assertTrue(p.position().distanceTo(edge) < 1.0E-6, "did not move");
			h.assertTrue(timer(p, AbilitySupport.key(t)) == null, "no cooldown after a failed teleport");
		} finally {
			remove(p);
		}

		// danger: a magma floor is never a destination
		for (int x = 1; x < 8; x++) h.setBlock(new BlockPos(x, 0, 5), Blocks.MAGMA_BLOCK);
		h.setBlock(new BlockPos(0, 0, 5), Blocks.STONE);
		ServerPlayer hot = player(h, 0.5, 1, 5.5, 0, 0);
		ahead(h, hot, 0.5, 1, 5.5);
		try {
			give(hot, 1, 0, source("enderman_hot", List.of(entry(TeleportBehavior.TYPE,
					"{\"trigger\":\"sneak_jump\",\"mode\":\"look\",\"range\":[6],\"cooldown\":[100]}")), List.of()));
			h.assertFalse(TraitEngine.fireTrigger(hot, Hook.SNEAK_JUMP, null), "only magma ahead: no teleport");
		} finally {
			remove(hot);
		}
		h.succeed();
	}

	@GameTest
	public void teleportRandomStaysWithinTheCappedRange(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		Vec3 start = h.absoluteVec(new Vec3(4.5, 1, 4.5));
		ServerPlayer p = player(h, 4.5, 1, 4.5, 0, 0);
		try {
			give(p, 1, 0, source("chorus", List.of(entry(TeleportBehavior.TYPE,
					"{\"trigger\":\"sneak_jump\",\"mode\":\"random\",\"range\":[3],\"cooldown\":[5]}")), List.of()));
			ActiveBehavior<?> t = active(p, TeleportBehavior.TYPE);
			int moved = 0;
			for (int i = 0; i < 12; i++) {
				p.snapTo(start.x, start.y, start.z, 0.0F, 0.0F);
				expire(p, AbilitySupport.key(t));
				if (!TraitEngine.fireTrigger(p, Hook.SNEAK_JUMP, null)) continue;
				moved++;
				h.assertTrue(p.position().distanceTo(start) <= 3.0 + EPS, "within range 3: " + p.position().distanceTo(start));
				h.assertTrue(h.getLevel().noCollision(p, p.getBoundingBox()), "never inside a block");
				BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.01, p.getZ());
				h.assertFalse(h.getLevel().getBlockState(below).getCollisionShape(h.getLevel(), below).isEmpty(), "standing on something");
				h.assertValueEqual(timer(p, AbilitySupport.key(t)), now(h) + AbsorbCaps.ABILITY_MIN_COOLDOWN_TICKS, "cooldown 5 raised to the minimum 20");
			}
			h.assertTrue(moved >= 6, "most attempts find the floor: " + moved);

			TeleportBehavior.Params far = decode(TeleportBehavior.TYPE, "{\"trigger\":\"sneak_jump\",\"mode\":\"random\",\"range\":[40],\"cooldown\":[5]}");
			near(h, far.rangeAt(1), AbsorbCaps.TELEPORT_MAX_DISTANCE, EPS, "range capped at 16");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void teleportOnHurtRollsOnRealDamageOnly(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		SourceDefinition chorus = source("chorus_hurt", List.of(), List.of(entry(TeleportBehavior.TYPE,
				"{\"trigger\":\"on_hurt\",\"mode\":\"random\",\"range\":[3],\"chance\":[1.0],\"cooldown\":[60]}")));
		Vec3 start = h.absoluteVec(new Vec3(4.5, 1, 4.5));

		ServerPlayer weak = player(h, 4.5, 1, 4.5, 0, 0);
		try {
			give(weak, 0, 1, chorus);
			ActiveBehavior<?> t = active(weak, TeleportBehavior.TYPE);
			h.assertTrue(t.weakness(), "weakness side");
			h.assertFalse(TraitEngine.fireTrigger(weak, Hook.SNEAK_JUMP, null), "on_hurt ignores sneak_jump");
			WeaknessDamage.hurt(weak, 2.0F);
			h.assertTrue(weak.position().distanceTo(start) < EPS, "our own weakness damage never teleports");
			h.assertTrue(timer(weak, AbilitySupport.key(t)) == null, "no roll");
		} finally {
			remove(weak);
		}

		ServerPlayer hurt = player(h, 4.5, 1, 4.5, 0, 0);
		try {
			give(hurt, 0, 1, chorus);
			ActiveBehavior<?> t = active(hurt, TeleportBehavior.TYPE);
			boolean moved = false;
			for (int i = 0; i < 5 && !moved; i++) { // chance 1.0; a random search can miss the floor
				hurt.snapTo(start.x, start.y, start.z, 0.0F, 0.0F);
				hurt.hurtServer(h.getLevel(), hurt.damageSources().generic(), 1.0F + i);
				moved = timer(hurt, AbilitySupport.key(t)) != null;
			}
			h.assertTrue(moved, "real damage with chance 1 teleports");
			h.assertTrue(hurt.position().distanceTo(start) > 0.0 && hurt.position().distanceTo(start) <= 3.0 + EPS, "moved within range 3");
			h.assertValueEqual(timer(hurt, AbilitySupport.key(t)), now(h) + 60, "cooldown 60");
			Vec3 there = hurt.position();
			hurt.hurtServer(h.getLevel(), hurt.damageSources().generic(), 10.0F);
			h.assertTrue(hurt.position().distanceTo(there) < EPS, "on cooldown: stays");
		} finally {
			remove(hurt);
		}
		h.succeed();
	}

	// ---- sneak_detonate --------------------------------------------------------------------------------------

	@GameTest
	public void sneakDetonateExplodesAfterFuseWithoutBlockDamage(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		h.setBlock(new BlockPos(3, 1, 5), Blocks.GLASS);
		ServerPlayer p = player(h, 2.5, 1, 4.5, 0, 0);
		Mob zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(5, 1, 4));
		try {
			give(p, 1, 0, source("creeper", List.of(entry(SneakDetonateBehavior.TYPE, "{\"power\":[2.0,2.5,3.0],\"fuse\":30,\"cooldown\":[600,500,400]}")), List.of()));
			ActiveBehavior<?> d = active(p, SneakDetonateBehavior.TYPE);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_DOUBLE_TAP, null), "fuse lit");
			h.assertValueEqual(timer(p, SneakDetonateBehavior.fuseKey(d)), now(h) + 30, "30-tick fuse");
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.SNEAK_DOUBLE_TAP, null), "already burning");
			tick(p, SneakDetonateBehavior.TYPE);
			h.assertValueEqual(zombie.getHealth(), zombie.getMaxHealth(), "nothing before the fuse ends");

			expire(p, SneakDetonateBehavior.fuseKey(d));
			tick(p, SneakDetonateBehavior.TYPE);
			h.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "the blast hurt the zombie: " + zombie.getHealth());
			h.assertValueEqual(p.getHealth(), p.getMaxHealth(), "the player is excluded from their own blast");
			for (int x = 0; x < 8; x++) {
				for (int z = 0; z < 8; z++) h.assertBlockPresent(Blocks.STONE, new BlockPos(x, 0, z));
			}
			h.assertBlockPresent(Blocks.GLASS, new BlockPos(3, 1, 5));
			h.assertValueEqual(timer(p, AbilitySupport.key(d)), now(h) + 600, "cooldown starts at detonation");
			h.assertTrue(timer(p, SneakDetonateBehavior.fuseKey(d)) == null, "fuse spent");
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.SNEAK_DOUBLE_TAP, null), "on cooldown");

			SneakDetonateBehavior.Params big = decode(SneakDetonateBehavior.TYPE, "{\"power\":[10],\"fuse\":0,\"cooldown\":[600]}");
			near(h, big.powerAt(1), AbsorbCaps.ABILITY_MAX_EXPLOSION_POWER, EPS, "power capped at 3");
		} finally {
			zombie.discard();
			remove(p);
		}
		h.succeed();
	}

	/**
	 * Review M2: with PvP off the blast neither hurts nor pushes another player, and it never touches pets, villagers,
	 * armor stands or items; a zombie is hit. With PvP on, the other player is hurt.
	 */
	@GameTest
	public void sneakDetonateSparesPlayersPetsVillagersAndThings(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		GameRules rules = h.getLevel().getGameRules();
		boolean pvp = rules.get(GameRules.PVP);
		ServerPlayer p = player(h, 3.5, 1, 3.5, 0, 0);
		ServerPlayer other = player(h, 5.5, 1, 3.5, 0, 0);
		Mob villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(3, 1, 5));
		Wolf wolf = h.spawnWithNoFreeWill(EntityTypes.WOLF, new BlockPos(1, 1, 3));
		ArmorStand stand = h.spawn(EntityTypes.ARMOR_STAND, new BlockPos(3, 1, 1));
		Mob zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(5, 1, 5));
		Vec3 at = h.absoluteVec(new Vec3(2.0, 1.0, 4.5));
		ItemEntity item = new ItemEntity(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.DIAMOND));
		item.setDeltaMovement(Vec3.ZERO);
		h.getLevel().addFreshEntity(item);
		try {
			wolf.tame(other);
			give(p, 3, 0, source("creeper_safe", List.of(entry(SneakDetonateBehavior.TYPE, "{\"power\":[3],\"fuse\":0,\"cooldown\":[600]}")), List.of()));
			ActiveBehavior<?> d = active(p, SneakDetonateBehavior.TYPE);
			rules.set(GameRules.PVP, false, h.getLevel().getServer());
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_DOUBLE_TAP, null), "fuse 0: detonates at once");
			h.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "a hostile mob is hit: " + zombie.getHealth());
			h.assertValueEqual(other.getHealth(), other.getMaxHealth(), "PvP off: the other player is not hurt");
			near(h, other.getDeltaMovement().length(), 0.0, EPS, "PvP off: and not knocked back");
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "villager spared");
			near(h, villager.getDeltaMovement().length(), 0.0, EPS, "villager not pushed");
			h.assertValueEqual(wolf.getHealth(), wolf.getMaxHealth(), "tamed wolf spared");
			near(h, wolf.getDeltaMovement().length(), 0.0, EPS, "tamed wolf not pushed");
			h.assertTrue(stand.isAlive(), "armor stand spared");
			h.assertTrue(item.isAlive(), "item spared");
			near(h, item.getDeltaMovement().length(), 0.0, EPS, "item not pushed");

			rules.set(GameRules.PVP, true, h.getLevel().getServer());
			expire(p, AbilitySupport.key(d));
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_DOUBLE_TAP, null), "second blast");
			h.assertTrue(other.getHealth() < other.getMaxHealth(), "PvP on: the other player is hurt: " + other.getHealth());
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "villager still spared");
		} finally {
			rules.set(GameRules.PVP, pvp, h.getLevel().getServer());
			villager.discard();
			wolf.discard();
			stand.discard();
			zombie.discard();
			item.discard();
			remove(other);
			remove(p);
		}
		h.succeed();
	}

	/** Review m6: an entry's timer keys are built once, not per call (per-tick hot paths). */
	@GameTest
	public void abilityKeysAreCachedPerEntry(GameTestHelper h) {
		ServerPlayer p = player(h, 1.5, 1, 1.5, 0, 0);
		try {
			give(p, 1, 1, source("keys", List.of(entry(SneakDetonateBehavior.TYPE, "{\"power\":[1],\"cooldown\":[600]}")),
					List.of(entry(SneakDetonateBehavior.TYPE, "{\"power\":[1],\"cooldown\":[600]}"))));
			List<ActiveBehavior<?>> both = TraitEngine.active(p).all().stream().filter(a -> a.type() == SneakDetonateBehavior.TYPE).toList();
			h.assertValueEqual(both.size(), 2, "trait and weakness entry");
			ActiveBehavior<?> trait = both.stream().filter(a -> !a.weakness()).findFirst().orElseThrow();
			ActiveBehavior<?> weak = both.stream().filter(ActiveBehavior::weakness).findFirst().orElseThrow();
			h.assertTrue(AbilitySupport.key(trait) == AbilitySupport.key(trait), "same key instance every call");
			h.assertTrue(SneakDetonateBehavior.fuseKey(trait) == SneakDetonateBehavior.fuseKey(trait), "same suffixed key instance");
			h.assertValueEqual(AbilitySupport.key(trait), Identifier.fromNamespaceAndPath("absorbaholic", "sneak_detonate/absorbaholic_test/behc/keys"),
					"key format unchanged");
			h.assertValueEqual(SneakDetonateBehavior.fuseKey(weak),
					Identifier.fromNamespaceAndPath("absorbaholic", "sneak_detonate/absorbaholic_test/behc/keys/weakness/fuse"), "weakness side keeps its own keys");
			h.assertFalse(AbilitySupport.key(trait).equals(AbilitySupport.key(weak)), "sides differ");
			h.assertTrue(FlightBehavior.lockKey(trait) == FlightBehavior.lockKey(trait), "other suffixes are cached too");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- shoot_projectile ------------------------------------------------------------------------------------

	@GameTest
	public void shootProjectileFansCountWithCooldownAndCaps(GameTestHelper h) {
		ServerPlayer p = player(h, 1.5, 1, 4.5, 0, 0);
		ahead(h, p, 1.5, 1, 4.5);
		try {
			give(p, 3, 0, source("blaze", List.of(entry(ShootProjectileBehavior.TYPE,
					"{\"projectile\":\"small_fireball\",\"count\":[1,2,3],\"cooldown\":[40,30,20]}")), List.of()));
			ActiveBehavior<?> s = active(p, ShootProjectileBehavior.TYPE);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "fired");
			List<SmallFireball> balls = owned(h, p, SmallFireball.class);
			h.assertValueEqual(balls.size(), 3, "level III: three fireballs");
			Vec3 view = p.getViewVector(1.0F);
			double widest = 0.0;
			for (SmallFireball b : balls) {
				Vec3 d = b.getDeltaMovement().normalize();
				double off = Math.toDegrees(Math.acos(Math.min(1.0, d.dot(view))));
				h.assertTrue(off <= 5.1, "within the 10° fan around the look direction: " + off);
				for (SmallFireball c : balls) widest = Math.max(widest, Math.toDegrees(Math.acos(Math.min(1.0, d.dot(c.getDeltaMovement().normalize())))));
			}
			near(h, widest, 10.0, 0.2, "10° between the outer fireballs");
			h.assertValueEqual(timer(p, AbilitySupport.key(s)), now(h) + 20, "level III cooldown 20");
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "on cooldown");
			balls.forEach(Entity::discard);

			give(p, 1, 0, source("blaze_many", List.of(entry(ShootProjectileBehavior.TYPE, "{\"projectile\":\"small_fireball\",\"count\":[20],\"cooldown\":[5]}")), List.of()));
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "fired");
			List<SmallFireball> many = owned(h, p, SmallFireball.class);
			h.assertValueEqual(many.size(), AbsorbCaps.ABILITY_MAX_TARGETS, "count capped at 8");
			h.assertValueEqual(timer(p, AbilitySupport.key(active(p, ShootProjectileBehavior.TYPE))), now(h) + AbsorbCaps.ABILITY_MIN_COOLDOWN_TICKS,
					"cooldown never below 20");
			many.forEach(Entity::discard);
		} finally {
			remove(p);
		}
		h.succeed();
	}

	@GameTest
	public void shootProjectileFangsBulletsAndDragonBreath(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		ServerPlayer p = player(h, 0.5, 1, 4.5, 0, 0);
		ahead(h, p, 0.5, 1, 4.5);
		Mob zombie = null;
		try {
			give(p, 1, 0, source("evoker", List.of(entry(ShootProjectileBehavior.TYPE,
					"{\"projectile\":\"evoker_fangs\",\"count\":[5,8,12],\"damage\":[6,6,6],\"cooldown\":[160,120,80]}")), List.of()));
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "fangs");
			List<EvokerFangs> fangs = h.getLevel().getEntitiesOfClass(EvokerFangs.class, p.getBoundingBox().inflate(8), f -> f.getOwner() == p);
			h.assertValueEqual(fangs.size(), 5, "level I: five fangs");
			Vec3 dir = p.getViewVector(1.0F).multiply(1.0, 0.0, 1.0).normalize();
			for (EvokerFangs f : fangs) {
				near(h, f.getY(), p.getY(), EPS, "fangs on the floor");
				Vec3 rel = f.position().subtract(p.position()).multiply(1.0, 0.0, 1.0);
				near(h, rel.subtract(dir.scale(rel.dot(dir))).length(), 0.0, 1.0E-3, "in a line along the look direction");
				h.assertTrue(rel.dot(dir) > 1.0, "in front of the player");
			}
			fangs.forEach(Entity::discard);

			// shulker bullet: no hostile ahead → no shot, no cooldown; a zombie ahead → a homing bullet
			give(p, 1, 0, source("shulker", List.of(entry(ShootProjectileBehavior.TYPE,
					"{\"projectile\":\"shulker_bullet\",\"count\":[1,1,1],\"cooldown\":[80,60,40]}")), List.of()));
			ActiveBehavior<?> s = active(p, ShootProjectileBehavior.TYPE);
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "no target");
			h.assertTrue(timer(p, AbilitySupport.key(s)) == null, "no cooldown without a target");
			zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(5, 1, 4));
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "target ahead");
			List<ShulkerBullet> bullets = owned(h, p, ShulkerBullet.class);
			h.assertValueEqual(bullets.size(), 1, "one bullet");
			h.assertValueEqual(timer(p, AbilitySupport.key(s)), now(h) + 80, "cooldown 80");
			bullets.forEach(Entity::discard);

			// dragon fireball: the owner's own breath cloud never hurts them; someone else's does
			give(p, 1, 0, source("dragon", List.of(entry(ShootProjectileBehavior.TYPE,
					"{\"projectile\":\"dragon_fireball\",\"count\":[1,1,1],\"cooldown\":[200,140,100]}")), List.of()));
			AreaEffectCloud own = new AreaEffectCloud(h.getLevel(), p.getX(), p.getY(), p.getZ());
			own.setOwner(p);
			AreaEffectCloud other = new AreaEffectCloud(h.getLevel(), p.getX(), p.getY(), p.getZ());
			other.setOwner(zombie);
			DamageSource ownBreath = p.damageSources().indirectMagic(own, p);
			DamageSource otherBreath = p.damageSources().indirectMagic(other, zombie);
			h.assertFalse(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(p, ownBreath, 6.0F), "own breath: immune");
			h.assertTrue(ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(p, otherBreath, 6.0F), "a zombie's breath still hurts");
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "dragon fireball");
			List<Projectile> dragonBalls = owned(h, p, Projectile.class);
			h.assertTrue(dragonBalls.size() == 1 && dragonBalls.getFirst().getType() == EntityTypes.DRAGON_FIREBALL, "one dragon fireball: " + dragonBalls);
			dragonBalls.forEach(Entity::discard);
		} finally {
			if (zombie != null) zombie.discard();
			remove(p);
		}
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void shootProjectileSnowballDealsItsDamage(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		ServerPlayer p = player(h, 0.5, 1, 4.5, 0, 0);
		look(h, p, new Vec3(0.5, 1, 4.5), new Vec3(4.5, 2.4, 4.5));
		Mob villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(4, 1, 4));
		give(p, 1, 0, source("snow_golem", List.of(entry(ShootProjectileBehavior.TYPE,
				"{\"projectile\":\"snowball\",\"count\":[1,2,3],\"damage\":[1.5,1.5,2],\"cooldown\":[20,15,10]}")), List.of()));
		float max = villager.getMaxHealth();
		h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "fired");
		h.assertValueEqual(owned(h, p, Snowball.class).size(), 1, "level I: one snowball");
		h.succeedWhen(() -> {
			h.assertTrue(villager.getHealth() < max, "hit");
			near(h, max - villager.getHealth(), 1.5, 1.0E-4, "a snowball deals its damage (vanilla 0)");
			villager.discard();
			remove(p);
		});
	}

	@GameTest(maxTicks = 120)
	public void shootProjectileFireballNeverBreaksBlocks(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		for (int y = 1; y <= 4; y++) {
			for (int z = 2; z <= 6; z++) h.setBlock(new BlockPos(6, y, z), Blocks.OAK_PLANKS);
		}
		ServerPlayer p = player(h, 0.5, 1, 4.5, 0, 0);
		ahead(h, p, 0.5, 1, 4.5);
		give(p, 3, 0, source("ghast", List.of(entry(ShootProjectileBehavior.TYPE,
				"{\"projectile\":\"fireball\",\"count\":[1,1,1],\"explosion_power\":[1,1,2],\"cooldown\":[100,80,60]}")), List.of()));
		h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "fired");
		List<Fireball> fired = owned(h, p, Fireball.class);
		h.assertValueEqual(fired.size(), 1, "one fireball");
		Fireball ball = fired.getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(ball.isRemoved(), "exploded on the wall");
			for (int y = 1; y <= 4; y++) {
				for (int z = 2; z <= 6; z++) h.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(6, y, z));
			}
			for (int x = 0; x < 8; x++) {
				for (int z = 0; z < 8; z++) {
					h.assertBlockPresent(Blocks.STONE, new BlockPos(x, 0, z));
					h.assertBlockNotPresent(Blocks.FIRE, new BlockPos(x, 1, z));
				}
			}
			remove(p);
		});
	}

	/**
	 * Review M2 (fireball): the blast next to the wall spares a villager, a tamed wolf, an armor stand and an item, and
	 * still hurts a zombie.
	 */
	@GameTest(maxTicks = 120)
	public void shootProjectileFireballBlastSparesPetsVillagersAndThings(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		for (int y = 1; y <= 4; y++) {
			for (int z = 1; z <= 7; z++) h.setBlock(new BlockPos(7, y, z), Blocks.STONE);
		}
		ServerPlayer p = player(h, 0.5, 1, 4.5, 0, 0);
		ahead(h, p, 0.5, 1, 4.5);
		Mob villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(6, 1, 2));
		Wolf wolf = h.spawnWithNoFreeWill(EntityTypes.WOLF, new BlockPos(6, 1, 6));
		wolf.tame(p);
		ArmorStand stand = h.spawn(EntityTypes.ARMOR_STAND, new BlockPos(5, 1, 6));
		Mob zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(5, 1, 2));
		Vec3 at = h.absoluteVec(new Vec3(6.0, 1.0, 4.5));
		ItemEntity item = new ItemEntity(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.DIAMOND));
		item.setDeltaMovement(Vec3.ZERO);
		h.getLevel().addFreshEntity(item);
		give(p, 3, 0, source("ghast_safe", List.of(entry(ShootProjectileBehavior.TYPE,
				"{\"projectile\":\"fireball\",\"count\":[1],\"explosion_power\":[2],\"cooldown\":[100]}")), List.of()));
		h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "fired");
		Fireball ball = owned(h, p, Fireball.class).getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(ball.isRemoved(), "exploded on the wall");
			h.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "the zombie is hit: " + zombie.getHealth());
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "villager spared");
			h.assertValueEqual(wolf.getHealth(), wolf.getMaxHealth(), "tamed wolf spared");
			h.assertTrue(stand.isAlive(), "armor stand spared");
			h.assertTrue(item.isAlive(), "item spared");
			villager.discard();
			wolf.discard();
			stand.discard();
			zombie.discard();
			item.discard();
			remove(p);
		});
	}

	/** Review M3: a player's small fireball never places fire and never primes TNT or lights anything it hits. */
	@GameTest(maxTicks = 120)
	public void shootProjectileSmallFireballNeverPlacesFire(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		for (int y = 1; y <= 4; y++) {
			for (int z = 1; z <= 7; z++) h.setBlock(new BlockPos(6, y, z), y == 2 && z == 4 ? Blocks.TNT : Blocks.OAK_PLANKS);
		}
		ServerPlayer p = player(h, 0.5, 1, 4.5, 0, 0);
		look(h, p, new Vec3(0.5, 1, 4.5), new Vec3(6.0, 2.5, 4.5));
		give(p, 3, 0, source("blaze_safe", List.of(entry(ShootProjectileBehavior.TYPE,
				"{\"projectile\":\"small_fireball\",\"count\":[3],\"cooldown\":[20]}")), List.of()));
		h.assertTrue(h.getLevel().getGameRules().get(GameRules.MOB_GRIEFING), "mobGriefing on: vanilla would place fire");
		h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "fired");
		List<SmallFireball> balls = owned(h, p, SmallFireball.class);
		h.assertValueEqual(balls.size(), 3, "three fireballs");
		h.succeedWhen(() -> {
			for (SmallFireball b : balls) h.assertTrue(b.isRemoved(), "all hit the wall");
			for (int x = 0; x < 8; x++) {
				for (int y = 1; y <= 5; y++) {
					for (int z = 0; z < 8; z++) h.assertBlockNotPresent(Blocks.FIRE, new BlockPos(x, y, z));
				}
			}
			h.assertBlockPresent(Blocks.TNT, new BlockPos(6, 2, 4));
			h.assertTrue(h.getLevel().getEntitiesOfClass(PrimedTnt.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(10)).isEmpty(), "no primed TNT");
			remove(p);
		});
	}

	/** Review m5: the reserved wither_skull kind never breaks blocks, even with mobGriefing on, and is never dangerous. */
	@GameTest(maxTicks = 120)
	public void shootProjectileWitherSkullNeverBreaksBlocks(GameTestHelper h) {
		fill(h, 0, Blocks.STONE);
		for (int y = 1; y <= 4; y++) {
			for (int z = 1; z <= 7; z++) h.setBlock(new BlockPos(6, y, z), Blocks.DIRT);
		}
		ServerPlayer p = player(h, 0.5, 1, 4.5, 0, 0);
		ahead(h, p, 0.5, 1, 4.5);
		give(p, 1, 0, source("wither", List.of(entry(ShootProjectileBehavior.TYPE, "{\"projectile\":\"wither_skull\",\"count\":[1],\"cooldown\":[40]}")),
				List.of()));
		h.assertTrue(h.getLevel().getGameRules().get(GameRules.MOB_GRIEFING), "mobGriefing on: a vanilla skull would break dirt");
		h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "fired");
		List<WitherSkull> skulls = owned(h, p, WitherSkull.class);
		h.assertValueEqual(skulls.size(), 1, "one skull");
		WitherSkull skull = skulls.getFirst();
		skull.setDangerous(true);
		h.assertFalse(skull.isDangerous(), "never a dangerous (blue) skull");
		h.succeedWhen(() -> {
			h.assertTrue(skull.isRemoved(), "exploded on the wall");
			for (int y = 1; y <= 4; y++) {
				for (int z = 1; z <= 7; z++) h.assertBlockPresent(Blocks.DIRT, new BlockPos(6, y, z));
			}
			remove(p);
		});
	}

	// ---- sonic_boom ------------------------------------------------------------------------------------------

	@GameTest
	public void sonicBoomHitsTheFirstTargetThroughWalls(GameTestHelper h) {
		for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(2, y, 4), Blocks.STONE);
		ServerPlayer p = player(h, 0.5, 1, 4.5, 0, 0);
		ahead(h, p, 0.5, 1, 4.5);
		Mob first = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(5, 1, 4));
		Mob second = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(7, 1, 4));
		try {
			give(p, 1, 0, source("warden", List.of(entry(SonicBoomBehavior.TYPE, "{\"damage\":[6,8,10],\"range\":[10,15,20],\"cooldown\":[200,160,120]}")), List.of()));
			ActiveBehavior<?> b = active(p, SonicBoomBehavior.TYPE);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "boom");
			h.assertValueEqual(first.getHealth(), first.getMaxHealth() - 6.0F, "6 damage through the wall (armor bypassed)");
			h.assertTrue(first.getLastDamageSource() != null && first.getLastDamageSource().is(DamageTypes.SONIC_BOOM)
					&& first.getLastDamageSource().getEntity() == p, "vanilla sonic_boom type, attributed to the player");
			h.assertTrue(first.getDeltaMovement().dot(p.getViewVector(1.0F)) > 2.0, "knocked back 2.5 along the ray: " + first.getDeltaMovement());
			h.assertValueEqual(second.getHealth(), second.getMaxHealth(), "only the first target is hit");
			h.assertValueEqual(timer(p, AbilitySupport.key(b)), now(h) + 200, "cooldown 200");
			h.assertFalse(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "on cooldown");

			// out of range: particles only and half the cooldown
			give(p, 1, 0, source("warden_short", List.of(entry(SonicBoomBehavior.TYPE, "{\"damage\":[6],\"range\":[3],\"cooldown\":[200]}")), List.of()));
			ActiveBehavior<?> s = active(p, SonicBoomBehavior.TYPE);
			float health = first.getHealth();
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "fires at nothing");
			h.assertValueEqual(first.getHealth(), health, "range 3 does not reach 5 blocks");
			h.assertValueEqual(timer(p, AbilitySupport.key(s)), now(h) + 100, "a miss costs half the cooldown");

			SonicBoomBehavior.Params far = decode(SonicBoomBehavior.TYPE, "{\"damage\":[6],\"range\":[50],\"cooldown\":[200]}");
			near(h, far.rangeAt(1), AbsorbCaps.SONIC_BOOM_MAX_RANGE, EPS, "range capped at 20");
		} finally {
			first.discard();
			second.discard();
			remove(p);
		}
		h.succeed();
	}

	/**
	 * Review M4: through walls only hostile mobs are hit; a villager behind a wall is passed by (the zombie behind it is
	 * hit), in the open the villager is hit, and with PvP off another player in the way is passed by.
	 */
	@GameTest
	public void sonicBoomNeedsLineOfSightForNonHostiles(GameTestHelper h) {
		GameRules rules = h.getLevel().getGameRules();
		boolean pvp = rules.get(GameRules.PVP);
		for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(2, y, 4), Blocks.STONE);
		ServerPlayer p = player(h, 0.5, 1, 4.5, 0, 0);
		ahead(h, p, 0.5, 1, 4.5);
		Mob villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(4, 1, 4));
		Mob zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(7, 1, 4));
		ServerPlayer other = null;
		try {
			give(p, 1, 0, source("warden_los", List.of(entry(SonicBoomBehavior.TYPE, "{\"damage\":[6],\"range\":[15],\"cooldown\":[200]}")), List.of()));
			ActiveBehavior<?> b = active(p, SonicBoomBehavior.TYPE);
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "boom");
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth(), "villager behind the wall: not a target");
			h.assertValueEqual(zombie.getHealth(), zombie.getMaxHealth() - 6.0F, "zombie behind the wall: hit");

			for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(2, y, 4), Blocks.AIR);
			expire(p, AbilitySupport.key(b));
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "boom in the open");
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth() - 6.0F, "villager in sight: hit");

			villager.discard(); // hurt this tick (invulnerable): a fresh one takes its place
			villager = h.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(4, 1, 4));
			other = player(h, 2.5, 1, 4.5, 0, 0);
			rules.set(GameRules.PVP, false, h.getLevel().getServer());
			expire(p, AbilitySupport.key(b));
			h.assertTrue(TraitEngine.fireTrigger(p, Hook.SNEAK_SWING, null), "boom past a player");
			h.assertValueEqual(other.getHealth(), other.getMaxHealth(), "PvP off: the player is not hurt");
			near(h, other.getDeltaMovement().length(), 0.0, EPS, "PvP off: nor knocked back");
			h.assertValueEqual(villager.getHealth(), villager.getMaxHealth() - 6.0F, "the ray passed the player and hit the villager");
		} finally {
			rules.set(GameRules.PVP, pvp, h.getLevel().getServer());
			villager.discard();
			zombie.discard();
			if (other != null) remove(other);
			remove(p);
		}
		h.succeed();
	}

	// ---- helpers ---------------------------------------------------------------------------------------------

	private static ServerPlayer player(GameTestHelper h, double x, double y, double z, float yRot, float xRot) {
		TestSupport.setMode(h, true);
		ServerPlayer p = TestSupport.survivalPlayer(h);
		Vec3 at = h.absoluteVec(new Vec3(x, y, z));
		p.snapTo(at.x, at.y, at.z, yRot, xRot);
		return p;
	}

	/** Places the player at relative {@code from} looking with its eyes at relative {@code target} (test rotation safe). */
	private static void look(GameTestHelper h, ServerPlayer p, Vec3 from, Vec3 target) {
		Vec3 at = h.absoluteVec(from);
		Vec3 d = h.absoluteVec(target).subtract(at.add(0.0, p.getEyeHeight(), 0.0));
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		p.snapTo(at.x, at.y, at.z, yaw, pitch);
		p.setYHeadRot(yaw); // a living entity looks along its head rotation
		p.setYBodyRot(yaw);
	}

	/** Places the player at relative (x, y, z) looking horizontally along the structure's +X. */
	private static void ahead(GameTestHelper h, ServerPlayer p, double x, double y, double z) {
		look(h, p, new Vec3(x, y, z), new Vec3(x + 10.0, y + p.getEyeHeight(), z));
	}

	private static void remove(ServerPlayer p) {
		if (!p.hasDisconnected() && p.level().getServer().getPlayerList().getPlayer(p.getUUID()) == p) {
			p.level().getServer().getPlayerList().remove(p);
		}
	}

	/** A test source matching nothing, max level 3. */
	private static SourceDefinition source(String path, List<BehaviorEntry<?>> trait, List<BehaviorEntry<?>> weakness) {
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", "behc/" + path),
				new SourceTargets(SourceKind.BLOCK, List.of(), List.of()), Optional.empty(), 0xFFFFFF, Tier.COMMON, 3,
				new SourceDefinition.Side("test_trait", List.of(), trait),
				new SourceDefinition.Side("test_weakness", List.of(), weakness));
	}

	/** Decodes params with the type's real codec (the same path source JSON takes). */
	private static <P> P decode(BehaviorType<P> type, String json) {
		return type.codec().codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow(IllegalArgumentException::new);
	}

	private static <P> BehaviorEntry<P> entry(BehaviorType<P> type, String json) {
		return new BehaviorEntry<>(type, decode(type, json));
	}

	private static void rejects(GameTestHelper h, BehaviorType<?> type, String json, String what) {
		DataResult<?> result = type.codec().codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json));
		h.assertTrue(result.error().isPresent(), "rejected: " + what);
	}

	/** Gives the player {@code sources} (in this order) at the given levels and rebuilds synchronously. */
	private static void give(ServerPlayer p, int traitLevel, int weaknessLevel, SourceDefinition... sources) {
		List<SourceDefinition> before = SourceRegistry.all();
		List<SourceDefinition> with = new ArrayList<>(before);
		for (SourceDefinition s : sources) {
			with.removeIf(d -> d.id().equals(s.id()));
			with.add(s);
		}
		SourceRegistry.set(with);
		try {
			PlayerTraits traits = PlayerTraits.EMPTY;
			for (SourceDefinition s : sources) traits = traits.with(new TraitEntry(s.id(), traitLevel, weaknessLevel, false, false));
			PlayerData.setTraits(p, traits);
			TraitEngine.recompute(p);
		} finally {
			SourceRegistry.set(before);
		}
	}

	/** The player's first active entry of {@code type} (engine-built). */
	private static ActiveBehavior<?> active(ServerPlayer p, BehaviorType<?> type) {
		return TraitEngine.active(p).all().stream().filter(a -> a.type() == type).findFirst()
				.orElseThrow(() -> new IllegalStateException("no active " + type.id()));
	}

	/** Runs the tick hook of the player's engine-built entries of {@code type} (ignoring their interval). */
	private static void tick(ServerPlayer p, BehaviorType<?> type) {
		for (ActiveBehavior<?> a : TraitEngine.active(p).forHook(Hook.TICK)) {
			if (a.type() == type) a.tick(p);
		}
	}

	private static long now(GameTestHelper h) {
		return h.getLevel().getServer().getTickCount();
	}

	private static Long timer(ServerPlayer p, Identifier key) {
		return PlayerData.runtime(p).abilityCooldowns.get(key);
	}

	/** Makes a timer run out now (instead of waiting for it). */
	private static void expire(ServerPlayer p, Identifier key) {
		PlayerData.runtime(p).abilityCooldowns.computeIfPresent(key, (k, v) -> AbilitySupport.now(p));
	}

	private static void near(GameTestHelper h, double actual, double expected, double eps, String message) {
		h.assertTrue(Math.abs(actual - expected) <= eps, message + ": expected " + expected + ", got " + actual);
	}

	private static void fill(GameTestHelper h, int y, Block block) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, y, z), block);
		}
	}

	private static <T extends Entity> List<T> owned(GameTestHelper h, ServerPlayer p, Class<T> type) {
		return h.getLevel().getEntitiesOfClass(type, new AABB(p.blockPosition()).inflate(8),
				e -> e instanceof Projectile projectile && projectile.getOwner() == p);
	}

	/** FoodData's private exhaustion level (no getter in either version). */
	private static float exhaustion(ServerPlayer p) throws ReflectiveOperationException {
		Field f = FoodData.class.getDeclaredField("exhaustionLevel");
		f.setAccessible(true);
		return f.getFloat(p.getFoodData());
	}
}
