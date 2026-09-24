package dev.absorbaholic.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
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
import dev.absorbaholic.trait.BehaviorEntry;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.trait.WeaknessDamage;
import dev.absorbaholic.trait.behavior.AttackEffectBehavior;
import dev.absorbaholic.trait.behavior.CombatBehaviors;
import dev.absorbaholic.trait.behavior.DamageDealtMultiplierBehavior;
import dev.absorbaholic.trait.behavior.DamageMultiplierBehavior;
import dev.absorbaholic.trait.behavior.DurabilityMultiplierBehavior;
import dev.absorbaholic.trait.behavior.KillRewardBehavior;
import dev.absorbaholic.trait.behavior.MiscBehaviors;
import dev.absorbaholic.trait.behavior.RetaliateBehavior;
import dev.absorbaholic.trait.behavior.StruckByBehavior;
import dev.absorbaholic.trait.behavior.WipeOnDeathBehavior;
import dev.absorbaholic.trait.behavior.XpMultiplierBehavior;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * WP-BEH-A server gametests: one per behavior type of the combat and misc groups, each proving the effect through the
 * real engine wiring (damage pipeline mixins, Fabric damage / kill events, the projectile mixin, the XP orb and
 * durability mixins) with the params of the shipped source JSON, plus a check that every shipped source using these
 * types resolves.
 *
 * <p>Shipped sources are read from the mod's data, reduced to the behavior types that are registered (another package's
 * type may still be pending) and resolved under a test id with no targets, so lookups by other tests never see them.
 * They exist in {@link SourceRegistry} only inside a synchronous block. Mock players never tick by themselves: a
 * player's hurt cooldown is reset by hand where a test hits the same entity twice.
 */
public class BehaviorAGameTests {
	private static final double EPS = 1.0E-4;

	// ---- damage_multiplier (reference type) --------------------------------------------------------------------

	@GameTest
	public void damageMultiplierScalesAndImmunizes(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		// squid weakness "calamari": #is_fire ×[1.3, 1.6, 2.0]; the extra goes through the gate
		ServerPlayer squid = player(h);
		try {
			give(squid, 1, 2, shipped(h, "squid"));
			squid.hurtServer(level, squid.damageSources().inFire(), 5.0F);
			near(h, squid.getHealth(), 12.0, EPS, "squid weakness II: 5 fire × 1.6 = 8");
		} finally {
			remove(squid);
		}
		// blaze trait: #is_fire ×[0.6, 0.4, 0.25]
		ServerPlayer blaze = player(h);
		try {
			give(blaze, 2, 0, shipped(h, "blaze"));
			blaze.hurtServer(level, blaze.damageSources().inFire(), 5.0F);
			near(h, blaze.getHealth(), 18.0, EPS, "blaze trait II: 5 fire × 0.4 = 2");
		} finally {
			remove(blaze);
		}
		// cactus trait: cactus / sweet berry bush ×0 = immunity; /kill is never blocked
		ServerPlayer cactus = player(h);
		try {
			give(cactus, 1, 0, shipped(h, "cactus"));
			h.assertFalse(cactus.hurtServer(level, cactus.damageSources().cactus(), 5.0F), "cactus immunity cancels the hit");
			h.assertFalse(cactus.hurtServer(level, cactus.damageSources().sweetBerryBush(), 5.0F), "sweet berry bush immunity");
			near(h, cactus.getHealth(), 20.0, EPS, "immune: no damage");
			cactus.hurtServer(level, cactus.damageSources().genericKill(), 3.0F);
			near(h, cactus.getHealth(), 17.0, EPS, "/kill is never blocked");
		} finally {
			remove(cactus);
		}
		h.succeed();
	}

	// ---- damage_dealt_multiplier -------------------------------------------------------------------------------

	@GameTest
	public void damageDealtMultiplierScalesProjectiles(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer p = player(h);
		try {
			// skeleton trait "marksman": #is_projectile ×[1.15, 1.3, 1.5]
			give(p, 2, 0, shipped(h, "skeleton"));
			LivingEntity shot = pig(h);
			shot.hurtServer(level, projectile(p), 2.0F);
			near(h, shot.getHealth(), 10.0 - 2.6, EPS, "arrow 2 × 1.3");
			LivingEntity punched = pig(h);
			punched.hurtServer(level, p.damageSources().playerAttack(p), 2.0F);
			near(h, punched.getHealth(), 8.0, EPS, "melee is not a projectile: unchanged");

			// skeleton III (1.5) × illager III (1.3) = 1.95, within the cap
			give(p, 3, 0, shipped(h, "skeleton"), shipped(h, "illager"));
			LivingEntity both = pig(h);
			both.hurtServer(level, projectile(p), 2.0F);
			near(h, both.getHealth(), 10.0 - 3.9, EPS, "arrow 2 × 1.5 × 1.3");

			// the combined factor is clamped to 2
			SourceDefinition strong = testSource("dealt_strong",
					List.of(decode(DamageDealtMultiplierBehavior.TYPE, "{\"multiplier\": [3, 3, 3], \"target\": \"minecraft:pig\"}")), List.of());
			give(p, 3, 0, shipped(h, "skeleton"), strong);
			LivingEntity capped = pig(h);
			capped.hurtServer(level, projectile(p), 2.0F);
			near(h, capped.getHealth(), 6.0, EPS, "1.5 × 3 clamped to 2");
			// the target filter: a cow is not a pig
			LivingEntity cow = h.spawnWithNoFreeWill(EntityTypes.COW, new BlockPos(1, 1, 1));
			cow.hurtServer(level, projectile(p), 2.0F);
			near(h, cow.getHealth(), 10.0 - 3.0, EPS, "target filter: only the skeleton factor on a cow");
			cow.discard();
			for (LivingEntity e : List.of(shot, punched, both, capped)) e.discard();
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- attack_effect -----------------------------------------------------------------------------------------

	@GameTest
	public void attackEffectPoisonsOnMeleeOrProjectile(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer p = player(h);
		try {
			// cave_spider trait "venomous" (melee only): poison 0, duration [60, 100, 140]
			give(p, 2, 0, shipped(h, "cave_spider"));
			LivingEntity bitten = pig(h);
			bitten.hurtServer(level, p.damageSources().playerAttack(p), 1.0F);
			MobEffectInstance poison = bitten.getEffect(MobEffects.POISON);
			h.assertTrue(poison != null && poison.getAmplifier() == 0 && poison.getDuration() == 100, "melee → poison I for 100 ticks: " + poison);
			LivingEntity shot = pig(h);
			shot.hurtServer(level, projectile(p), 1.0F);
			h.assertFalse(shot.hasEffect(MobEffects.POISON), "no damage_tag: projectiles don't count");
			LivingEntity killed = pig(h);
			killed.hurtServer(level, p.damageSources().playerAttack(p), 1000.0F);
			h.assertFalse(killed.isAlive(), "killed");
			h.assertFalse(killed.hasEffect(MobEffects.POISON), "no effect on a target that died");

			// bogged trait "toxic_arrows" (#is_projectile): poison 0, duration [60, 80, 120]
			give(p, 2, 0, shipped(h, "bogged"));
			LivingEntity arrowed = pig(h);
			arrowed.hurtServer(level, projectile(p), 1.0F);
			MobEffectInstance arrowPoison = arrowed.getEffect(MobEffects.POISON);
			h.assertTrue(arrowPoison != null && arrowPoison.getDuration() == 80, "projectile → poison for 80 ticks: " + arrowPoison);
			LivingEntity meleed = pig(h);
			meleed.hurtServer(level, p.damageSources().playerAttack(p), 1.0F);
			h.assertFalse(meleed.hasEffect(MobEffects.POISON), "damage_tag is_projectile: melee doesn't count");

			// ignite payload (code-built entry decoded by the real codec)
			SourceDefinition burning = testSource("attack_ignite",
					List.of(decode(AttackEffectBehavior.TYPE, "{\"chance\": [1, 1, 1], \"ignite_seconds\": [2, 4, 6]}")), List.of());
			give(p, 2, 0, burning);
			LivingEntity lit = pig(h);
			lit.hurtServer(level, p.damageSources().playerAttack(p), 1.0F);
			h.assertValueEqual(lit.getRemainingFireTicks(), 80, "ignite_seconds II = 4 s");
			for (LivingEntity e : List.of(bitten, shot, killed, arrowed, meleed, lit)) e.discard();
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- retaliate ---------------------------------------------------------------------------------------------

	@GameTest
	public void retaliateHitsBackMeleeAttackers(GameTestHelper h) throws ReflectiveOperationException {
		ServerLevel level = h.getLevel();
		// cactus trait "prickly": thorns damage [1.0, 1.5, 2.0], chance [0.5, 0.75, 1.0]
		ServerPlayer p = player(h);
		try {
			give(p, 3, 0, shipped(h, "cactus"));
			LivingEntity attacker = pig(h);
			h.assertTrue(p.hurtServer(level, p.damageSources().mobAttack(attacker), 2.0F), "the pig hurt the player");
			near(h, attacker.getHealth(), 8.0, EPS, "prickly III: 2 thorns damage back");
			h.assertTrue(attacker.getLastDamageSource() != null && attacker.getLastDamageSource().is(DamageTypes.THORNS)
					&& attacker.getLastDamageSource().getEntity() == p, "vanilla thorns damage credited to the player");

			resetHurtCooldown(p);
			LivingEntity far = h.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(1, 1, 9));
			h.assertTrue(p.distanceTo(far) > 6.0F, "far pig beyond 6 blocks");
			p.hurtServer(level, p.damageSources().mobAttack(far), 2.0F);
			near(h, far.getHealth(), 10.0, EPS, "beyond 6 blocks: no retaliation");

			resetHurtCooldown(p);
			LivingEntity shooter = pig(h);
			Entity arrow = EntityTypes.ARROW.create(level, EntitySpawnReason.TRIGGERED);
			p.hurtServer(level, p.damageSources().source(DamageTypes.MOB_PROJECTILE, arrow, shooter), 2.0F);
			near(h, shooter.getHealth(), 10.0, EPS, "projectiles never trigger retaliation");
			for (LivingEntity e : List.of(attacker, far, shooter)) e.discard();
		} finally {
			remove(p);
		}

		// pufferfish trait "puff_up": poison [0, 0, 1], duration [60, 100, 140]; cooldown per attacker
		ServerPlayer puffer = player(h);
		try {
			give(puffer, 1, 0, shipped(h, "pufferfish"));
			LivingEntity attacker = pig(h);
			puffer.hurtServer(level, puffer.damageSources().mobAttack(attacker), 2.0F);
			MobEffectInstance poison = attacker.getEffect(MobEffects.POISON);
			h.assertTrue(poison != null && poison.getAmplifier() == 0 && poison.getDuration() == 60, "puff_up I poisons the attacker: " + poison);
			attacker.removeAllEffects();
			DamageSource again = puffer.damageSources().mobAttack(attacker);
			ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(puffer, again, 2.0F, 2.0F, false);
			h.assertFalse(attacker.hasEffect(MobEffects.POISON), "same attacker within 10 ticks: on cooldown");
			LivingEntity other = pig(h);
			ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(puffer, puffer.damageSources().mobAttack(other), 2.0F, 2.0F, false);
			h.assertTrue(other.hasEffect(MobEffects.POISON), "the cooldown is per attacker");
			for (LivingEntity e : List.of(attacker, other)) e.discard();
		} finally {
			remove(puffer);
		}
		h.succeed();
	}

	// ---- kill_reward -------------------------------------------------------------------------------------------

	@GameTest(maxTicks = 60)
	public void killRewardHealsOncePerCooldown(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		// sculk trait "soul_harvest": heal [1, 2, 3]
		SourceDefinition sculk = shipped(h, "sculk");
		ServerPlayer p = player(h);
		give(p, 2, 0, sculk);
		keep(h, p, 2, 0, sculk);
		p.setHealth(10.0F);
		LivingEntity cod = h.spawnWithNoFreeWill(EntityTypes.COD, new BlockPos(1, 1, 1));
		cod.hurtServer(level, p.damageSources().playerAttack(p), 1000.0F);
		h.assertFalse(cod.isAlive(), "cod killed");
		near(h, p.getHealth(), 10.0, EPS, "victims under 4 max health give nothing");
		pig(h).hurtServer(level, p.damageSources().playerAttack(p), 1000.0F);
		near(h, p.getHealth(), 12.0, EPS, "soul_harvest II heals 2 per kill");
		pig(h).hurtServer(level, p.damageSources().playerAttack(p), 1000.0F);
		near(h, p.getHealth(), 12.0, EPS, "one reward per 10 ticks");
		h.runAfterDelay(11, () -> {
			pig(h).hurtServer(level, p.damageSources().playerAttack(p), 1000.0F);
			near(h, p.getHealth(), 14.0, EPS, "after the cooldown: rewarded again");
			remove(p);

			// optional effect payload
			ServerPlayer q = player(h);
			try {
				SourceDefinition regen = testSource("kill_regen", List.of(decode(KillRewardBehavior.TYPE,
						"{\"heal\": [0, 0, 0], \"effect\": \"minecraft:regeneration\", \"amplifier\": [0, 1, 2], \"duration\": [40, 60, 80]}")), List.of());
				give(q, 2, 0, regen);
				pig(h).hurtServer(level, q.damageSources().playerAttack(q), 1000.0F);
				MobEffectInstance effect = q.getEffect(MobEffects.REGENERATION);
				h.assertTrue(effect != null && effect.getAmplifier() == 1 && effect.getDuration() == 60, "kill reward effect II: " + effect);
			} finally {
				remove(q);
			}
			h.succeed();
		});
	}

	// ---- struck_by ---------------------------------------------------------------------------------------------

	@GameTest(maxTicks = 60)
	public void struckBySnowballsHurtThroughTheGate(GameTestHelper h) throws ReflectiveOperationException {
		ServerLevel level = h.getLevel();
		// magma_block weakness "snow_allergy": struck_by snowball, damage [1, 2, 3]
		SourceDefinition magma = shipped(h, "magma_block");
		ServerPlayer p = player(h);
		give(p, 1, 2, magma);
		keep(h, p, 1, 2, magma);
		Mob golem = h.spawnWithNoFreeWill(EntityTypes.SNOW_GOLEM, new BlockPos(1, 1, 3));

		Projectile egg = (Projectile) EntityTypes.EGG.create(level, EntitySpawnReason.TRIGGERED);
		TraitEngine.onHitByProjectile(p, egg);
		near(h, p.getHealth(), 20.0, EPS, "an egg is not a snowball");

		hitWith(p, snowball(level, p, golem));
		near(h, p.getHealth(), 18.0, EPS, "snow_allergy II: a zero-damage snowball deals 2");
		DamageSource last = p.getLastDamageSource();
		h.assertTrue(last != null && last.is(WeaknessDamage.TYPE) && last.getEntity() == golem, "weakness damage credited to the thrower");

		resetHurtCooldown(p);
		hitWith(p, snowball(level, p, golem));
		near(h, p.getHealth(), 18.0, EPS, "at most once per 10 ticks");
		h.runAfterDelay(11, () -> {
			try {
				resetHurtCooldown(p);
				hitWith(p, snowball(level, p, null));
				near(h, p.getHealth(), 16.0, EPS, "after the cooldown (no thrower needed)");
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			} finally {
				remove(p);
				golem.discard();
			}
			h.succeed();
		});
	}

	// ---- xp_multiplier -----------------------------------------------------------------------------------------

	@GameTest
	public void xpMultiplierScalesOrbsOnly(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer lapis = player(h);
		ServerPlayer sculk = player(h);
		ServerPlayer capped = player(h);
		try {
			// lapis trait "wisdom" ×[1.2, 1.4, 1.6]; float factors are within 1e-7 of the expected integers, so the
			// stochastic rounding has no measurable chance to round the other way
			give(lapis, 2, 0, shipped(h, "lapis"));
			h.assertValueEqual(orb(level, lapis, 10), 14, "wisdom II: orb 10 × 1.4");
			int before = lapis.totalExperience;
			lapis.giveExperiencePoints(10);
			h.assertValueEqual(lapis.totalExperience - before, 10, "direct XP (commands, bottles' own grants) unchanged");

			// sculk weakness "soul_tax" ×[0.75, 0.5, 0.25]
			give(sculk, 1, 2, shipped(h, "sculk"));
			h.assertValueEqual(orb(level, sculk, 10), 5, "soul_tax II: orb 10 × 0.5");

			// combined factors are clamped to 2.5
			SourceDefinition big = testSource("xp_big", List.of(decode(XpMultiplierBehavior.TYPE, "{\"multiplier\": [2, 2, 2]}")), List.of());
			give(capped, 3, 0, shipped(h, "lapis"), big);
			h.assertValueEqual(orb(level, capped, 10), 25, "1.6 × 2 clamped to 2.5");
		} finally {
			remove(lapis);
			remove(sculk);
			remove(capped);
		}
		h.succeed();
	}

	// ---- durability_multiplier ---------------------------------------------------------------------------------

	@GameTest
	public void durabilityMultiplierWearsItemsFaster(GameTestHelper h) {
		ServerPlayer p = player(h);
		ServerPlayer plain = player(h);
		try {
			// gold weakness "soft_metal" ×[1.25, 1.5, 2.0]
			give(p, 1, 2, shipped(h, "gold"));
			ItemStack sword = new ItemStack(Items.IRON_SWORD);
			sword.hurtAndBreak(2, p, EquipmentSlot.MAINHAND);
			h.assertValueEqual(sword.getDamageValue(), 3, "soft_metal II: 2 × 1.5");
			give(p, 1, 3, shipped(h, "gold"));
			ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
			pick.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
			h.assertValueEqual(pick.getDamageValue(), 2, "soft_metal III: 1 × 2");

			TraitEngine.recompute(plain);
			ItemStack other = new ItemStack(Items.IRON_SWORD);
			other.hurtAndBreak(2, plain, EquipmentSlot.MAINHAND);
			h.assertValueEqual(other.getDamageValue(), 2, "players without the weakness unchanged");
		} finally {
			remove(p);
			remove(plain);
		}
		h.succeed();
	}

	// ---- wipe_on_death -----------------------------------------------------------------------------------------

	@GameTest
	public void wipeOnDeathFollowsTheWeaknessLevel(GameTestHelper h) {
		ServerPlayer p = player(h);
		try {
			SourceDefinition egg = shipped(h, "dragon_egg");
			give(p, 1, 1, egg);
			h.assertTrue(TraitEngine.wipesOnDeath(p), "dragon egg weakness active → any death wipes");
			give(p, 1, 0, egg);
			h.assertFalse(TraitEngine.wipesOnDeath(p), "pure dragon egg (weakness 0) never wipes");
			give(p, 1, 1, egg);
			TestSupport.setMode(h, false);
			try {
				TraitEngine.recompute(p);
				h.assertFalse(TraitEngine.wipesOnDeath(p), "dormant (mode OFF) never wipes");
			} finally {
				TestSupport.setMode(h, true);
			}
		} finally {
			remove(p);
		}
		h.succeed();
	}

	// ---- shipped data ------------------------------------------------------------------------------------------

	/**
	 * Every shipped source using one of this package's types decodes those params without errors or unknown-param
	 * warnings, and a source whose behavior types are all registered is loaded.
	 */
	@GameTest
	public void shippedSourcesUsingTheseTypesResolve(GameTestHelper h) {
		Set<Identifier> mine = new HashSet<>();
		for (BehaviorType<?> t : CombatBehaviors.TYPES) mine.add(t.id());
		for (BehaviorType<?> t : MiscBehaviors.TYPES) mine.add(t.id());
		mine.add(DamageMultiplierBehavior.TYPE.id());
		mine.add(WipeOnDeathBehavior.TYPE.id());
		for (Identifier id : mine) h.assertTrue(BehaviorRegistry.get(id).isPresent(), id + " registered");

		Set<Identifier> seen = new LinkedHashSet<>();
		List<String> problems = new ArrayList<>();
		int checked = 0;
		SourceLoader.LoadResult last = SourceLoader.lastResult();
		for (SourceLoader.RawSource raw : shippedFiles(h)) {
			if (!(raw.parsed() instanceof SourceSpecParser.Result.Parsed(SourceSpec spec))) continue;
			Set<Identifier> used = typesOf(spec);
			used.retainAll(mine);
			if (used.isEmpty()) continue;
			checked++;
			seen.addAll(used);
			Set<Identifier> unknown = SourceResolver.unknownBehaviorTypes(spec);
			for (Identifier t : used) {
				if (unknown.contains(t)) problems.add(raw.id() + ": " + t + " not registered");
			}
			SourceSpec registeredOnly = registeredOnly(spec);
			List<String> errors = new ArrayList<>();
			List<String> warnings = new ArrayList<>();
			SourceResolver.resolve(raw.id(), registeredOnly, RegistryOps.create(JsonOps.INSTANCE, h.getLevel().registryAccess()), errors::add, warnings::add);
			if (!errors.isEmpty()) problems.add(raw.id() + ": " + errors);
			warnings.stream().filter(w -> !w.endsWith(" in targets, ignored")).forEach(w -> problems.add(raw.id() + ": warning " + w));
			if (unknown.isEmpty() && last.source(raw.id()).isEmpty()) problems.add(raw.id() + ": all types registered but not loaded");
		}
		h.assertTrue(problems.isEmpty(), problems.size() + " problems: " + String.join(" | ", problems));
		h.assertValueEqual(seen, mine, "every type of this package is used by shipped data");
		h.assertTrue(checked >= 30, "shipped sources using these types: " + checked);
		h.succeed();
	}

	// ---- helpers -----------------------------------------------------------------------------------------------

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

	/** A fresh no-AI pig (10 HP, no armor) next to the test player. */
	private static LivingEntity pig(GameTestHelper h) {
		return h.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(1, 1, 2));
	}

	/** Arrow damage owned by {@code owner} ({@code #minecraft:is_projectile}). */
	private static DamageSource projectile(ServerPlayer owner) {
		Entity arrow = EntityTypes.ARROW.create(owner.level(), EntitySpawnReason.TRIGGERED);
		return owner.damageSources().source(DamageTypes.ARROW, arrow, owner);
	}

	private static Projectile snowball(ServerLevel level, ServerPlayer target, Entity owner) {
		Projectile snowball = (Projectile) EntityTypes.SNOWBALL.create(level, EntitySpawnReason.TRIGGERED);
		snowball.setPos(target.position());
		if (owner != null) snowball.setOwner(owner);
		return snowball;
	}

	/** The real projectile hit path ({@code Projectile#onHit}, where the engine's ProjectileMixin sits). */
	private static void hitWith(ServerPlayer target, Projectile projectile) throws ReflectiveOperationException {
		var onHit = Projectile.class.getDeclaredMethod("onHit", HitResult.class);
		onHit.setAccessible(true);
		onHit.invoke(projectile, new EntityHitResult(target));
	}

	/** Touches an experience orb worth {@code value}; returns the XP gained. */
	private static int orb(ServerLevel level, ServerPlayer p, int value) {
		p.takeXpDelay = 0;
		int before = p.totalExperience;
		new ExperienceOrb(level, p.getX(), p.getY(), p.getZ(), value).playerTouch(p);
		return p.totalExperience - before;
	}

	/** Mock players never tick, so their hurt cooldown never runs out: 26.2 has a public field, 26.3 a setter. */
	private static void resetHurtCooldown(Entity e) throws ReflectiveOperationException {
		try {
			Entity.class.getMethod("setInvulnerableTime", int.class).invoke(e, 0);
		} catch (NoSuchMethodException missing) {
			Entity.class.getField("invulnerableTime").setInt(e, 0);
		}
	}

	private static <P> BehaviorEntry<P> decode(BehaviorType<P> type, String json) {
		return new BehaviorEntry<>(type, type.codec().codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow());
	}

	private static List<SourceLoader.RawSource> shippedFiles(GameTestHelper h) {
		return SourceLoader.read(h.getLevel().getServer().getResourceManager()).stream()
				.filter(r -> r.id().getNamespace().equals("absorbaholic")).toList();
	}

	private static Set<Identifier> typesOf(SourceSpec spec) {
		Set<Identifier> types = new LinkedHashSet<>();
		for (SourceSpec.SideSpec side : List.of(spec.trait(), spec.weakness())) {
			for (SourceSpec.BehaviorSpec b : side.behaviors()) {
				Identifier id = Identifier.tryParse(b.type());
				if (id != null) types.add(id);
			}
		}
		return types;
	}

	/** {@code spec} without behaviors whose type is not registered (yet). */
	private static SourceSpec registeredOnly(SourceSpec spec) {
		return new SourceSpec(spec.kind(), spec.targets(), spec.name(), spec.icon(), spec.color(), spec.tier(), spec.maxLevel(),
				registeredOnly(spec.trait()), registeredOnly(spec.weakness()));
	}

	private static SourceSpec.SideSpec registeredOnly(SourceSpec.SideSpec side) {
		return new SourceSpec.SideSpec(side.key(), side.attributes(), side.behaviors().stream().filter(b -> {
			Identifier id = Identifier.tryParse(b.type());
			return id != null && BehaviorRegistry.get(id).isPresent();
		}).toList());
	}

	/**
	 * The shipped source {@code absorbaholic:<path>} resolved from the mod's data exactly as the loader does, reduced to
	 * registered behavior types, under a test id with no targets.
	 */
	private static SourceDefinition shipped(GameTestHelper h, String path) {
		Identifier id = Identifier.fromNamespaceAndPath("absorbaholic", path);
		SourceLoader.RawSource raw = shippedFiles(h).stream().filter(r -> r.id().equals(id)).findFirst()
				.orElseThrow(() -> new IllegalStateException("no shipped source " + id));
		if (!(raw.parsed() instanceof SourceSpecParser.Result.Parsed(SourceSpec spec))) throw new IllegalStateException(id + " does not parse");
		List<String> errors = new ArrayList<>();
		SourceDefinition d = SourceResolver.resolve(id, registeredOnly(spec), RegistryOps.create(JsonOps.INSTANCE, h.getLevel().registryAccess()),
				errors::add, w -> {});
		if (d == null) throw new IllegalStateException(id + " does not resolve: " + errors);
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", "beh_a/" + path),
				new SourceTargets(d.kind(), List.of(), List.of()), Optional.empty(), d.color(), d.tier(), d.maxLevel(), d.trait(), d.weakness());
	}

	/** A code-built source with no targets. */
	private static SourceDefinition testSource(String path, List<BehaviorEntry<?>> trait, List<BehaviorEntry<?>> weakness) {
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", "beh_a/" + path),
				new SourceTargets(SourceKind.BLOCK, List.of(), List.of()), Optional.empty(), 0xFFFFFF, Tier.COMMON, 3,
				new SourceDefinition.Side("test_trait", List.of(), trait), new SourceDefinition.Side("test_weakness", List.of(), weakness));
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
}
