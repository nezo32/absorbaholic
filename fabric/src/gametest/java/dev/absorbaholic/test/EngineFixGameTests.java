package dev.absorbaholic.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.SourceSpec;
import dev.absorbaholic.core.SourceSpecParser;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.net.MovementPayload;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceLoader;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.registry.SourceResolver;
import dev.absorbaholic.registry.SourceTargets;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.AttributeEntry;
import dev.absorbaholic.trait.BehaviorEntry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.Hook;
import dev.absorbaholic.trait.MovementFlags;
import dev.absorbaholic.trait.MovementFlagsHolder;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.trait.WeaknessDamage;
import dev.absorbaholic.trait.behavior.DamageDealtMultiplierBehavior;
import dev.absorbaholic.trait.behavior.EnvironmentDamageBehavior;
import dev.absorbaholic.trait.behavior.HungerDrainBehavior;
import dev.absorbaholic.trait.behavior.KnockbackMultiplierBehavior;
import dev.absorbaholic.trait.behavior.MobAttitudeBehavior;
import dev.absorbaholic.trait.behavior.WalkOnFluidBehavior;
import dev.absorbaholic.world.AbsorbWorldSettings;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Engine review fixes (fix round 1), each proven through the real engine wiring: C1 (walk_on_fluid solid stands on
 * lava), M5 (knockback_multiplier and the shipped knockback weaknesses), m1 (weakness damage credited to a player is
 * not multiplied again), m2 (weakness-caused burning and starvation are charged to the damage gate), m3 (max-health
 * bonuses survive a relog and an End exit) and m8 (the emerald weakness makes iron golems hostile, no Bad Omen); plus the
 * final review's R3 (no rise in a lava fall), R5 (full health after a death respawn), R6 (fluid landing rules) and R7
 * (no fluid surface for vanilla clients).
 */
public class EngineFixGameTests {
	// ---- C1 --------------------------------------------------------------------------------------------------

	/**
	 * A Strider-trait player on a lava lake stands on it: dropped above it, it lands on the surface; walking across it
	 * stays on top; put inside the lava, it rises out. It never counts as in lava after landing and takes no damage.
	 */
	@GameTest(maxTicks = 100)
	public void striderWalksOnLava(GameTestHelper h) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				h.setBlock(new BlockPos(x, 1, z), x == 0 || z == 0 || x == 7 || z == 7 ? Blocks.STONE : Blocks.LAVA);
				h.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
				h.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
			}
		}
		ServerPlayer p = moddedPlayer(h, 2.5, 2.6, 2.5);
		try {
			give(p, 3, 0, source("strider", List.of(entry(WalkOnFluidBehavior.TYPE, "{\"fluid\":\"lava\",\"mode\":\"solid\",\"radius\":[0,0,0]}")), List.of()));
			double surface = h.absoluteVec(new Vec3(0, 2.0, 0)).y; // the lava source's collision top (block top)
			float health = p.getHealth();
			simulate(p, Vec3.ZERO, 20);
			h.assertTrue(p.onGround(), "landed on the lava surface, y=" + p.getY());
			h.assertTrue(Math.abs(p.getY() - surface) < 1.0E-3, "feet on the lava top: " + p.getY() + " vs " + surface);
			h.assertFalse(p.isInLava(), "not in lava while standing on it");
			simulate(p, new Vec3(0.0, 0.0, 1.0), 20); // walk across the lake (toward +z, stops at the wall)
			h.assertTrue(p.onGround() && Math.abs(p.getY() - surface) < 1.0E-3 && !p.isInLava(), "still on top after walking, y=" + p.getY());
			h.assertTrue(p.getZ() - h.absoluteVec(new Vec3(0, 0, 2.5)).z > 1.0, "actually walked: z=" + p.getZ());
			h.assertValueEqual(p.getHealth(), health, "no lava damage while walking on it");
			h.assertTrue(p.getRemainingFireTicks() <= 0, "not set on fire");

			// dunked into the lava (e.g. after sneaking): it rises back onto the surface
			Vec3 in = h.absoluteVec(new Vec3(4.5, 1.1, 4.5));
			p.snapTo(in.x, in.y, in.z, 0.0F, 0.0F);
			p.setDeltaMovement(Vec3.ZERO);
			simulate(p, Vec3.ZERO, 30);
			h.assertTrue(p.onGround() && Math.abs(p.getY() - surface) < 1.0E-3 && !p.isInLava(), "rose onto the surface, y=" + p.getY());

			// sneaking sinks again (vanilla lava physics)
			p.setShiftKeyDown(true);
			simulate(p, Vec3.ZERO, 10);
			h.assertTrue(p.getY() < surface - 0.05, "sneaking sinks into the lava, y=" + p.getY());
		} finally {
			remove(p);
		}
		h.succeed();
	}

	/** Final review R3: in a lava fall (falling, non-source lava) there is no surface, so nothing lifts the player. */
	@GameTest(maxTicks = 100)
	public void striderFallsThroughLavaFall(GameTestHelper h) {
		// a 1x1 shaft: stone floor at y=0, a lava source at y=6 under a stone lid, falling lava at y=1..5
		for (int y = 0; y <= 7; y++) {
			for (int x = 2; x <= 4; x++) {
				for (int z = 2; z <= 4; z++) {
					if (x != 3 || z != 3 || y == 0 || y == 7) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
				}
			}
		}
		h.setBlock(new BlockPos(3, 6, 3), Blocks.LAVA);
		for (int y = 1; y <= 5; y++) {
			h.setBlock(new BlockPos(3, y, 3), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 8));
		}
		ServerPlayer p = moddedPlayer(h, 3.5, 3.0, 3.5);
		try {
			p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
			give(p, 3, 0, source("strider_fall", List.of(entry(WalkOnFluidBehavior.TYPE, "{\"fluid\":\"lava\",\"mode\":\"solid\",\"radius\":[0,0,0]}")), List.of()));
			double start = p.getY();
			simulate(p, Vec3.ZERO, 20);
			h.assertTrue(p.getY() < start - 1.0, "no elevator in a lava fall: y=" + p.getY() + " from " + start);
			h.assertTrue(p.getY() < h.absoluteVec(new Vec3(0, 1.5, 0)).y, "fell to the shaft floor: y=" + p.getY());
		} finally {
			remove(p);
		}
		h.succeed();
	}

	/**
	 * Final review R6: landing on a walked surface keeps vanilla's fluid landing: water cancels the fall, lava halves it,
	 * stone takes it all (same fall distance for the three players).
	 */
	@GameTest(maxTicks = 100)
	public void fluidWalkLandingKeepsFluidFallRules(GameTestHelper h) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean rim = z == 0 || z == 7;
				h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				h.setBlock(new BlockPos(x, 1, z), !rim && x == 3 ? Blocks.LAVA : !rim && x == 5 ? Blocks.WATER : Blocks.STONE);
				for (int y = 2; y < 8; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
			}
		}
		SourceDefinition walker = source("fluid_walker", List.of(
				entry(WalkOnFluidBehavior.TYPE, "{\"fluid\":\"lava\",\"mode\":\"solid\",\"radius\":[0,0,0]}"),
				entry(WalkOnFluidBehavior.TYPE, "{\"fluid\":\"water\",\"mode\":\"solid\",\"radius\":[0,0,0]}")), List.of());
		ServerPlayer onStone = moddedPlayer(h, 1.5, 2.5, 3.5);
		ServerPlayer onLava = moddedPlayer(h, 3.5, 2.5, 3.5);
		ServerPlayer onWater = moddedPlayer(h, 5.5, 2.5, 3.5);
		try {
			ServerPlayer[] players = {onStone, onLava, onWater};
			float[] lost = new float[players.length];
			for (int i = 0; i < players.length; i++) {
				ServerPlayer p = players[i];
				give(p, 3, 0, walker);
				p.fallDistance = 10.0;
				p.setDeltaMovement(0.0, -0.5, 0.0);
				float health = p.getHealth();
				for (int t = 0; t < 5; t++) {
					// the client moves; the server checks fall damage from the reported move (as handleMovePlayer does)
					Vec3 from = p.position();
					simulate(p, Vec3.ZERO, 1);
					Vec3 moved = p.position().subtract(from);
					p.doCheckFallDamage(moved.x, moved.y, moved.z, p.onGround());
				}
				h.assertTrue(p.onGround(), "landed " + i + ", y=" + p.getY());
				lost[i] = health - p.getHealth();
			}
			h.assertTrue(lost[0] >= 6.0F, "stone: full fall damage, lost " + lost[0]);
			h.assertTrue(lost[1] > 0.0F && lost[1] <= lost[0] - 3.0F, "lava: halved fall, lost " + lost[1] + " vs stone " + lost[0]);
			h.assertValueEqual(lost[2], 0.0F, "water: no fall damage");
			h.assertFalse(onLava.isInLava(), "the lava lander stands on the surface");
		} finally {
			remove(onStone);
			remove(onLava);
			remove(onWater);
		}
		h.succeed();
	}

	/**
	 * Final review R7: a vanilla client can't simulate fluid walking, so its server entity has no fluid surface (the
	 * movement check would otherwise keep pulling the sinking client back on top). The modded case is
	 * {@link #striderWalksOnLava}.
	 */
	@GameTest(maxTicks = 100)
	public void vanillaClientGetsNoFluidSurface(GameTestHelper h) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				h.setBlock(new BlockPos(x, 1, z), x == 0 || z == 0 || x == 7 || z == 7 ? Blocks.STONE : Blocks.LAVA);
				h.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
				h.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
			}
		}
		ServerPlayer p = player(h, 3.5, 2.6, 3.5);
		try {
			p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
			give(p, 3, 0, source("strider_vanilla", List.of(entry(WalkOnFluidBehavior.TYPE, "{\"fluid\":\"lava\",\"mode\":\"solid\",\"radius\":[0,0,0]}")), List.of()));
			h.assertTrue(TraitEngine.active(p).all().stream().anyMatch(a -> a.type() == WalkOnFluidBehavior.TYPE), "the trait is active");
			h.assertFalse(((MovementFlagsHolder) p).absorbaholic$movement().has(MovementFlags.WALK_ON_LAVA), "no lava walking flag on the server");
			simulate(p, Vec3.ZERO, 20);
			h.assertTrue(p.isInLava() && p.getY() < h.absoluteVec(new Vec3(0, 2.0, 0)).y - 0.05, "sinks like vanilla, y=" + p.getY());
		} finally {
			remove(p);
		}
		h.succeed();
	}

	/** Final review R5: a death respawn with +20 max health (keep on death) starts at the full 40, not vanilla's 20. */
	@GameTest
	public void deathRespawnStartsAtFullTraitMaxHealth(GameTestHelper h) {
		SourceDefinition heart = source("heart_respawn", List.of(new AttributeEntry(Attributes.MAX_HEALTH,
				AttributeModifier.Operation.ADD_VALUE, LevelValue.constant(20.0))), List.of(), List.of(), List.of());
		MinecraftServer server = h.getLevel().getServer();
		boolean keep = AbsorbWorldSettings.get(server).keepOnDeath();
		ServerPlayer p = player(h, 1.5, 1.0, 1.5);
		ServerPlayer respawned = null;
		List<SourceDefinition> before = SourceRegistry.all();
		List<SourceDefinition> with = new ArrayList<>(before);
		with.add(heart);
		SourceRegistry.set(with);
		try {
			AbsorbWorldSettings.setKeepOnDeath(server, true);
			PlayerData.setTraits(p, PlayerTraits.EMPTY.with(new TraitEntry(heart.id(), 1, 0, false, false)));
			TraitEngine.recompute(p);
			h.assertValueEqual(p.getMaxHealth(), 40.0F, "trait max health");
			p.kill(h.getLevel());
			respawned = server.getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
			h.assertTrue(PlayerData.traits(respawned).get(heart.id()).isPresent(), "traits kept on death");
			TraitEngine.recompute(respawned);
			h.assertValueEqual(respawned.getMaxHealth(), 40.0F, "max health back");
			h.assertValueEqual(respawned.getHealth(), 40.0F, "respawned at full trait max health");

			// consumed once: a later rebuild never heals
			respawned.setHealth(30.0F);
			TraitEngine.recompute(respawned);
			h.assertValueEqual(respawned.getHealth(), 30.0F, "no second top-up");
		} finally {
			SourceRegistry.set(before);
			AbsorbWorldSettings.setKeepOnDeath(server, keep);
			remove(p);
			if (respawned != null) remove(respawned);
		}
		h.succeed();
	}

	/** One tick of player movement as the client runs it: fluid state, travel, block effects. */
	private static void simulate(ServerPlayer p, Vec3 input, int ticks) {
		for (int i = 0; i < ticks; i++) {
			p.baseTick();
			Vec3 from = p.position();
			p.travel(input);
			p.applyEffectsFromBlocks(from, p.position());
		}
	}

	// ---- M5 --------------------------------------------------------------------------------------------------

	/** The shipped slime block weakness doubles knockback at level III (it used to be an inert negative resistance). */
	@GameTest
	public void knockbackMultiplierAmplifiesKnockback(GameTestHelper h) {
		ServerPlayer p = player(h, 1.5, 1.0, 1.5);
		try {
			double plain = knockbackX(p);
			h.assertTrue(Math.abs(plain + 0.4) < 1.0E-6, "vanilla knockback 0.4: " + plain);
			give(p, 0, 3, shipped(h, "slime_block"));
			h.assertTrue(TraitEngine.active(p).any(Hook.KNOCKBACK), "slime block weakness uses knockback_multiplier");
			double wobbly = knockbackX(p);
			h.assertTrue(Math.abs(wobbly + 0.8) < 1.0E-6, "Wobbly III: knockback ×2 = 0.8, got " + wobbly);
			give(p, 0, 1, shipped(h, "slime_block"));
			h.assertTrue(Math.abs(knockbackX(p) + 0.6) < 1.0E-6, "Wobbly I: ×1.5");

			// the combined factor is capped (×3), and a trait can reduce knockback (floor ×0.5)
			SourceDefinition huge = source("kb_huge", List.of(), List.of(entry(KNOCKBACK, "{\"multiplier\":[9]}")));
			give(p, 0, 1, huge);
			h.assertTrue(Math.abs(knockbackX(p) + 0.4 * AbsorbCaps.KNOCKBACK_FACTOR_MAX) < 1.0E-6, "capped at ×" + AbsorbCaps.KNOCKBACK_FACTOR_MAX);
			SourceDefinition steady = source("kb_steady", List.of(entry(KNOCKBACK, "{\"multiplier\":[0.5]}")), List.of());
			give(p, 1, 0, steady);
			h.assertTrue(Math.abs(knockbackX(p) + 0.2) < 1.0E-6, "a trait halves knockback");
			h.assertTrue(KNOCKBACK.codec().codec().parse(JsonOps.INSTANCE, JsonParser.parseString("{\"multiplier\":[0.1]}")).error().isPresent(),
					"multiplier below the floor is rejected");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	private static final BehaviorType<KnockbackMultiplierBehavior.Params> KNOCKBACK = KnockbackMultiplierBehavior.TYPE;

	/** Knockback of strength 0.4 from +x on a still, airborne player; returns the resulting x velocity. */
	private static double knockbackX(ServerPlayer p) {
		p.setDeltaMovement(Vec3.ZERO);
		p.setOnGround(false);
		p.knockback(0.4, 1.0, 0.0, p.damageSources().generic(), 1.0F);
		double x = p.getDeltaMovement().x;
		p.setDeltaMovement(Vec3.ZERO);
		return x;
	}

	/** No shipped add_value knockback_resistance below 0 (inert, vanilla clamps the attribute at 0). */
	@GameTest
	public void shippedKnockbackWeaknessesUseTheMultiplier(GameTestHelper h) {
		for (String name : List.of("slime_block", "breeze", "husk", "leaves", "ocelot")) {
			SourceDefinition d = shipped(h, name);
			h.assertTrue(d.weakness().attributes().stream().noneMatch(a -> a.attribute().is(Attributes.KNOCKBACK_RESISTANCE)),
					name + ": no inert negative knockback resistance");
			h.assertTrue(d.weakness().behaviors().stream().anyMatch(b -> b.type() == KNOCKBACK), name + ": knockback_multiplier weakness");
		}
		h.succeed();
	}

	// ---- m1 --------------------------------------------------------------------------------------------------

	/** Weakness damage credited to a player (struck_by, inverted Instant Health) is never multiplied by its traits. */
	@GameTest
	public void outgoingMultiplierSkipsWeaknessDamage(GameTestHelper h) {
		ServerPlayer attacker = player(h, 1.5, 1.0, 1.5);
		ServerPlayer victim = player(h, 3.5, 1.0, 1.5);
		try {
			give(attacker, 1, 0, source("dealt_x2", List.of(entry(DamageDealtMultiplierBehavior.TYPE, "{\"multiplier\":[2.0]}")), List.of()));
			DamageSource weakness = WeaknessDamage.source(victim, attacker);
			h.assertTrue(weakness != null, "weakness damage type exists");
			h.assertValueEqual(TraitEngine.modifyOutgoingDamage(attacker, victim, weakness, 4.0F), 4.0F, "gated weakness damage stays 4");
			h.assertValueEqual(TraitEngine.modifyOutgoingDamage(attacker, victim, attacker.damageSources().playerAttack(attacker), 4.0F), 8.0F,
					"a normal hit is still doubled");
			h.assertValueEqual(TraitEngine.modifyOutgoingDamage(attacker, victim, attacker.damageSources().genericKill(), 4.0F), 4.0F,
					"/kill style damage is never scaled");
		} finally {
			remove(attacker);
			remove(victim);
		}
		h.succeed();
	}

	// ---- m2 --------------------------------------------------------------------------------------------------

	/** Burning started by a weakness and starvation sped up by a weakness spend (and respect) the damage gate. */
	@GameTest
	public void weaknessCausedBurningAndStarvationAreGated(GameTestHelper h) {
		ServerPlayer burn = player(h, 1.5, 1.0, 1.5);
		ServerPlayer starve = player(h, 3.5, 1.0, 1.5);
		ServerPlayer plain = player(h, 5.5, 1.0, 1.5);
		try {
			long now = h.getLevel().getServer().getTickCount();
			give(burn, 0, 1, source("sunburn", List.of(), List.of(entry(EnvironmentDamageBehavior.TYPE, "{\"ignite_seconds\":[4]}"))));
			tick(burn, EnvironmentDamageBehavior.TYPE);
			h.assertTrue(burn.getRemainingFireTicks() >= 80, "the weakness set the player on fire");
			burn.setHealth(10.0F);
			hurt(burn, burn.damageSources().onFire(), 1.0F);
			h.assertValueEqual(burn.getHealth(), 9.0F, "the burn itself still hurts");
			h.assertTrue(Math.abs(PlayerData.runtime(burn).damageGate.remaining(now) - (AbsorbCaps.WEAKNESS_DAMAGE_BUDGET - 1.0F)) < 1.0E-4,
					"the burn spent 1 HP of the weakness budget");
			PlayerData.runtime(burn).damageGate.allowDirect(now, 100.0F, 9.0F, 20.0F); // spend the rest of the window
			hurt(burn, burn.damageSources().onFire(), 1.0F);
			h.assertValueEqual(burn.getHealth(), 9.0F, "budget spent: the weakness burn is held back");

			give(starve, 0, 1, source("hungry", List.of(), List.of(entry(HungerDrainBehavior.TYPE, "{\"multiplier\":[1.5]}"))));
			starve.setHealth(10.0F);
			hurt(starve, starve.damageSources().starve(), 1.0F);
			h.assertValueEqual(starve.getHealth(), 9.0F, "starvation still hurts");
			h.assertTrue(PlayerData.runtime(starve).damageGate.remaining(now) < AbsorbCaps.WEAKNESS_DAMAGE_BUDGET - 0.5F,
					"starvation with a hunger weakness spent the budget");

			// no weakness involved: vanilla burning is not charged
			give(plain, 1, 0, source("plain", List.of(entry(HungerDrainBehavior.TYPE, "{\"multiplier\":[0.8]}")), List.of()));
			plain.setHealth(10.0F);
			plain.igniteForSeconds(4.0F);
			hurt(plain, plain.damageSources().onFire(), 1.0F);
			hurt(plain, plain.damageSources().starve(), 1.0F);
			h.assertValueEqual(PlayerData.runtime(plain).damageGate.remaining(now), AbsorbCaps.WEAKNESS_DAMAGE_BUDGET, "a trait never spends the gate");
		} finally {
			remove(burn);
			remove(starve);
			remove(plain);
		}
		h.succeed();
	}

	private static void hurt(ServerPlayer p, DamageSource source, float amount) {
		try { // mock players never tick, so their hurt cooldown never runs out: 26.2 has a public field, 26.3 a setter
			try {
				Entity.class.getMethod("setInvulnerableTime", int.class).invoke(p, 0);
			} catch (NoSuchMethodException missing) {
				Entity.class.getField("invulnerableTime").setInt(p, 0);
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		p.hurtServer(p.level(), source, amount);
	}

	// ---- m3 --------------------------------------------------------------------------------------------------

	/** 35/40 HP with a +20 max-health trait is still 35/40 after a save + load, and after an End exit copy. */
	@GameTest
	public void maxHealthBonusSurvivesRelogAndEndExit(GameTestHelper h) {
		SourceDefinition heart = source("heart", List.of(new AttributeEntry(Attributes.MAX_HEALTH,
				AttributeModifier.Operation.ADD_VALUE, LevelValue.constant(20.0))), List.of(), List.of(), List.of());
		ServerPlayer p = player(h, 1.5, 1.0, 1.5);
		ServerPlayer loaded = player(h, 3.5, 1.0, 1.5);
		ServerPlayer exited = player(h, 5.5, 1.0, 1.5);
		List<SourceDefinition> before = SourceRegistry.all();
		List<SourceDefinition> with = new ArrayList<>(before);
		with.add(heart);
		SourceRegistry.set(with);
		try {
			PlayerData.setTraits(p, PlayerTraits.EMPTY.with(new TraitEntry(heart.id(), 1, 0, false, false)));
			TraitEngine.recompute(p);
			h.assertValueEqual(p.getMaxHealth(), 40.0F, "trait max health");
			p.setHealth(35.0F);

			// relog: save the player, load the data into a fresh entity (vanilla clamps Health to 20 on load)
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
			p.saveWithoutId(out);
			CompoundTag tag = out.buildResult();
			tag.remove("UUID");
			loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
			h.assertTrue(loaded.getHealth() <= 20.0F, "vanilla clamped the loaded health: " + loaded.getHealth());
			TraitEngine.recompute(loaded);
			h.assertValueEqual(loaded.getMaxHealth(), 40.0F, "traits loaded");
			h.assertValueEqual(loaded.getHealth(), 35.0F, "health restored after the modifiers are back");

			// End exit: the new entity copies the old one (vanilla restoreFrom clamps health to 20, like a fresh entity)
			exited.setHealth(20.0F);
			ServerPlayerEvents.COPY_FROM.invoker().copyFromPlayer(p, exited, true);
			TraitEngine.recompute(exited);
			h.assertValueEqual(exited.getHealth(), 35.0F, "End exit keeps 35/40");

			// consumed once: a later rebuild never heals
			exited.setHealth(20.0F);
			TraitEngine.recompute(exited);
			h.assertValueEqual(exited.getHealth(), 20.0F, "no second restore");
		} finally {
			SourceRegistry.set(before);
			remove(p);
			remove(loaded);
			remove(exited);
		}
		h.succeed();
	}

	// ---- m8 --------------------------------------------------------------------------------------------------

	/** Emerald "Wanted Poster": iron golems attack the player; no permanent Bad Omen (no free raids). */
	@GameTest
	public void wantedPosterMakesIronGolemsHostile(GameTestHelper h) {
		ServerPlayer p = player(h, 1.5, 1.0, 1.5);
		try {
			Mob golem = h.spawn(EntityTypes.IRON_GOLEM, new Vec3(5.5, 1.0, 1.5));
			SourceDefinition emerald = shipped(h, "emerald");
			give(p, 0, 1, emerald);
			for (ActiveBehavior<?> a : TraitEngine.active(p).forHook(Hook.TICK)) a.tick(p);
			h.assertTrue(TraitEngine.active(p).all().stream().anyMatch(a -> a.type() == MobAttitudeBehavior.TYPE && a.weakness()),
					"emerald weakness is mob_attitude");
			h.assertTrue(golem.getTarget() == p, "a village iron golem hunts the player");
			h.assertFalse(p.hasEffect(MobEffects.BAD_OMEN), "no Bad Omen");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- helpers ---------------------------------------------------------------------------------------------

	private static ServerPlayer player(GameTestHelper h, double x, double y, double z) {
		TestSupport.setMode(h, true);
		ServerPlayer p = TestSupport.survivalPlayer(h);
		Vec3 at = h.absoluteVec(new Vec3(x, y, z));
		p.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
		return p;
	}

	/** {@link #player} whose client has the mod (declares the movement channel). */
	private static ServerPlayer moddedPlayer(GameTestHelper h, double x, double y, double z) {
		TestSupport.setMode(h, true);
		ServerPlayer p = TestSupport.survivalPlayer(h, List.of(MovementPayload.TYPE));
		Vec3 at = h.absoluteVec(new Vec3(x, y, z));
		p.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
		return p;
	}

	private static void remove(ServerPlayer p) {
		if (!p.hasDisconnected() && p.level().getServer().getPlayerList().getPlayer(p.getUUID()) == p) {
			p.level().getServer().getPlayerList().remove(p);
		}
	}

	private static SourceDefinition source(String path, List<BehaviorEntry<?>> trait, List<BehaviorEntry<?>> weakness) {
		return source(path, List.of(), trait, List.of(), weakness);
	}

	private static SourceDefinition source(String path, List<AttributeEntry> traitAttributes, List<BehaviorEntry<?>> trait,
			List<AttributeEntry> weaknessAttributes, List<BehaviorEntry<?>> weakness) {
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", "fix1/" + path),
				new SourceTargets(SourceKind.BLOCK, List.of(), List.of()), Optional.empty(), 0xFFFFFF, Tier.COMMON, 3,
				new SourceDefinition.Side("test_trait", traitAttributes, trait),
				new SourceDefinition.Side("test_weakness", weaknessAttributes, weakness));
	}

	private static <P> BehaviorEntry<P> entry(BehaviorType<P> type, String json) {
		return new BehaviorEntry<>(type, type.codec().codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow(IllegalArgumentException::new));
	}

	/** The shipped source {@code absorbaholic:<name>} fully resolved (all attributes and behaviors), under a test id. */
	private static SourceDefinition shipped(GameTestHelper h, String name) {
		Identifier id = Absorbaholic.id(name);
		SourceLoader.RawSource raw = SourceLoader.read(h.getLevel().getServer().getResourceManager()).stream()
				.filter(f -> f.id().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("no shipped source " + id));
		if (!(raw.parsed() instanceof SourceSpecParser.Result.Parsed(SourceSpec spec))) throw new IllegalStateException(id + " does not parse");
		List<String> errors = new ArrayList<>();
		SourceDefinition d = SourceResolver.resolve(id, spec, errors::add);
		if (d == null) throw new IllegalStateException(id + ": " + errors);
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", "fix1/shipped/" + name),
				new SourceTargets(d.targets().kind(), List.of(), List.of()), d.nameKey(), Optional.empty(), d.color(), d.tier(), d.maxLevel(),
				d.trait(), d.weakness());
	}

	/** Gives the player {@code sources} at the given levels (0/0 = no traits) and rebuilds synchronously. */
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
			if (traitLevel > 0 || weaknessLevel > 0) {
				for (SourceDefinition s : sources) traits = traits.with(new TraitEntry(s.id(), traitLevel, weaknessLevel, false, false));
			}
			PlayerData.setTraits(p, traits);
			TraitEngine.recompute(p);
		} finally {
			SourceRegistry.set(before);
		}
	}

	private static void tick(ServerPlayer p, BehaviorType<?> type) {
		for (ActiveBehavior<?> a : TraitEngine.active(p).forHook(Hook.TICK)) {
			if (a.type() == type) a.tick(p);
		}
	}
}
