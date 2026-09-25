package dev.absorbaholic.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.absorb.AbsorbFeedback;
import dev.absorbaholic.absorb.AbsorbHandler;
import dev.absorbaholic.absorb.AbsorbService;
import dev.absorbaholic.absorb.AbsorbTarget;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.MutationRoll;
import dev.absorbaholic.net.ChannelStatePayload;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.world.AbsorbWorldSettings;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Tester acceptance gametests against the product brief, using the REAL shipped sources (no test registry swaps):
 * the brief's examples resolve in game, unabsorbable blocks have no source, a whole absorb of shipped obsidian and a
 * shipped mob at the 25 % rule, levels I→II→III then refusal, the gamble's effect on the applied attributes,
 * per-player isolation, and Creative / Spectator never absorbing (bosses included) nor feeling weaknesses.
 * All work is synchronous with simulated game times, like {@code AbsorbInteractionGameTests}.
 */
public class AcceptanceGameTests {
	private static final BlockPos FEET = new BlockPos(1, 1, 1);
	private static final BlockPos TARGET = new BlockPos(1, 1, 3);
	private static final MutationRoll.Uniform NORMAL = () -> 0.5;

	// ---- helpers -----------------------------------------------------------------------------------------------

	private static ServerPlayer player(GameTestHelper h, BlockPos feet) {
		TestSupport.setMode(h, true);
		ServerLevel level = h.getLevel();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "tester-mock"), false);
		ServerPlayer p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		p.setGameMode(GameType.SURVIVAL);
		p.getInventory().clearContent();
		Vec3 at = h.absoluteVec(Vec3.atBottomCenterOf(feet));
		p.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
		p.setShiftKeyDown(true);
		return p;
	}

	private static void remove(ServerPlayer p) {
		if (!p.hasDisconnected() && p.level().getServer().getPlayerList().getPlayer(p.getUUID()) == p) {
			p.level().getServer().getPlayerList().remove(p);
		}
	}

	private static long now(GameTestHelper h) {
		return AbsorbHandler.now(h.getLevel().getServer());
	}

	private static SourceDefinition shipped(GameTestHelper h, String path) {
		Optional<SourceDefinition> s = SourceRegistry.byId(Absorbaholic.id(path));
		h.assertTrue(s.isPresent(), "shipped source absorbaholic:" + path + " is loaded");
		return s.get();
	}

	/** Full channel through the real validation: start, heartbeat + tick for CHANNEL_TICKS; asserts COMPLETED. */
	private static long absorb(GameTestHelper h, ServerPlayer p, AbsorbTarget target, long start, MutationRoll.Uniform roll) {
		AbsorbHandler.Check check = AbsorbHandler.start(p, target, start);
		h.assertTrue(check != null && check.ok(), "channel starts, got " + (check == null ? "null" : check.reasonKey()));
		AbsorbHandler.TickResult r = null;
		for (int i = 1; i <= AbsorbCaps.CHANNEL_TICKS; i++) {
			AbsorbHandler.start(p, target, start + i);
			r = AbsorbHandler.tick(p, start + i, roll);
			if (i < AbsorbCaps.CHANNEL_TICKS) h.assertValueEqual(r.status(), ChannelStatePayload.Status.PROGRESS, "progress at tick " + i);
		}
		h.assertValueEqual(r.status(), ChannelStatePayload.Status.COMPLETED, "completed after exactly 1.5 s");
		AbsorbHandler.cancel(p); // release the key
		return start + AbsorbCaps.CHANNEL_TICKS;
	}

	private static void assertNoDropsOrXp(GameTestHelper h) {
		AABB area = h.getBounds().inflate(3.0);
		h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "no item drops");
		h.assertTrue(h.getLevel().getEntitiesOfClass(ExperienceOrb.class, area).isEmpty(), "no experience orbs");
	}

	private static TraitEntry entry(GameTestHelper h, ServerPlayer p, SourceDefinition s) {
		Optional<TraitEntry> e = PlayerData.traits(p).get(s.id());
		h.assertTrue(e.isPresent(), "entry for " + s.id());
		return e.get();
	}

	/** Sum of our (absorbaholic:*) modifier amounts on an attribute, excluding the clamp correction. */
	private static boolean hasOurModifier(ServerPlayer p, Holder<Attribute> attribute) {
		AttributeInstance inst = p.getAttribute(attribute);
		if (inst == null) return false;
		for (AttributeModifier m : inst.getModifiers()) {
			if (m.id().getNamespace().equals(Absorbaholic.MOD_ID) && m.amount() != 0.0) return true;
		}
		return false;
	}

	// ---- registry: brief examples and unabsorbables ------------------------------------------------------------

	@GameTest
	public void shippedBriefExamplesResolveInGame(GameTestHelper h) {
		Map<Block, String> blocks = Map.ofEntries(
				Map.entry(Blocks.OBSIDIAN, "obsidian"), Map.entry(Blocks.SLIME_BLOCK, "slime_block"), Map.entry(Blocks.ICE, "ice"),
				Map.entry(Blocks.CACTUS, "cactus"), Map.entry(Blocks.SPONGE, "sponge"), Map.entry(Blocks.GLOWSTONE, "glow_blocks"),
				Map.entry(Blocks.MAGMA_BLOCK, "magma_block"), Map.entry(Blocks.BEDROCK, "bedrock"),
				Map.entry(Blocks.ANCIENT_DEBRIS, "ancient_debris"), Map.entry(Blocks.DRAGON_EGG, "dragon_egg"));
		for (Map.Entry<Block, String> e : blocks.entrySet()) {
			Optional<SourceDefinition> s = SourceRegistry.forBlock(e.getKey().defaultBlockState());
			h.assertTrue(s.isPresent() && s.get().id().equals(Absorbaholic.id(e.getValue())),
					e.getKey() + " → absorbaholic:" + e.getValue() + ", got " + s.map(SourceDefinition::id));
		}
		Optional<SourceDefinition> lava = SourceRegistry.forFluid(Fluids.LAVA.getSource(false));
		h.assertTrue(lava.isPresent() && lava.get().id().equals(Absorbaholic.id("lava")), "lava fluid → absorbaholic:lava");
		Map<EntityType<?>, String> mobs = Map.ofEntries(
				Map.entry(EntityTypes.ENDERMAN, "enderman"), Map.entry(EntityTypes.BEE, "bee"), Map.entry(EntityTypes.SPIDER, "spider"),
				Map.entry(EntityTypes.CREEPER, "creeper"), Map.entry(EntityTypes.PHANTOM, "phantom"), Map.entry(EntityTypes.BLAZE, "blaze"),
				Map.entry(EntityTypes.IRON_GOLEM, "iron_golem"), Map.entry(EntityTypes.CHICKEN, "chicken"),
				Map.entry(EntityTypes.WARDEN, "warden"), Map.entry(EntityTypes.WITHER, "wither"));
		for (Map.Entry<EntityType<?>, String> e : mobs.entrySet()) {
			Optional<SourceDefinition> s = SourceRegistry.forEntity(e.getKey());
			h.assertTrue(s.isPresent() && s.get().id().equals(Absorbaholic.id(e.getValue())),
					e.getKey() + " → absorbaholic:" + e.getValue() + ", got " + s.map(SourceDefinition::id));
		}
		// every loaded source: exactly one trait and one weakness that each carry something, max level III by default
		int blocksCount = 0;
		int mobsCount = 0;
		for (SourceDefinition s : SourceRegistry.all()) {
			if (!s.id().getNamespace().equals(Absorbaholic.MOD_ID)) continue;
			h.assertTrue(!s.trait().attributes().isEmpty() || !s.trait().behaviors().isEmpty(), s.id() + " trait does something");
			h.assertTrue(!s.weakness().attributes().isEmpty() || !s.weakness().behaviors().isEmpty(), s.id() + " weakness does something");
			if (s.targets().kind() == dev.absorbaholic.core.SourceKind.BLOCK) blocksCount++;
			else mobsCount++;
		}
		h.assertTrue(blocksCount >= 30 && mobsCount >= 30, "loaded ≥30 block and ≥30 mob sources, got " + blocksCount + "/" + mobsCount);
		h.succeed();
	}

	@GameTest
	public void unabsorbableBlocksHaveNoSource(GameTestHelper h) {
		for (Block b : List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.WATER, Blocks.NETHER_PORTAL, Blocks.END_PORTAL,
				Blocks.END_GATEWAY, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK)) {
			h.assertTrue(SourceRegistry.forBlock(b.defaultBlockState()).isEmpty(), b + " must not be absorbable");
		}
		h.assertTrue(SourceRegistry.forFluid(Fluids.WATER.getSource(false)).isEmpty(), "water (fluid) must not be absorbable");
		h.assertTrue(SourceRegistry.forFluid(Fluids.FLOWING_WATER.getFlowing(7, false)).isEmpty(), "flowing water must not be absorbable");
		h.assertTrue(SourceRegistry.forEntity(EntityTypes.PLAYER).isEmpty(), "players must not be absorbable");

		// the channel itself refuses a command block even when a player looks right at it
		ServerPlayer p = player(h, FEET);
		try {
			h.setBlock(TARGET, Blocks.COMMAND_BLOCK);
			p.lookAt(EntityAnchorArgument.Anchor.EYES, h.absoluteVec(Vec3.atCenterOf(TARGET)));
			AbsorbHandler.Check c = AbsorbHandler.start(p, AbsorbTarget.block(h.absolutePos(TARGET)), now(h));
			h.assertTrue(c == null || !c.ok(), "no channel on a command block");
			h.assertBlockPresent(Blocks.COMMAND_BLOCK, TARGET);
			h.setBlock(TARGET, Blocks.AIR);
		} finally {
			AbsorbHandler.cancel(p);
			remove(p);
		}
		h.succeed();
	}

	// ---- end to end with shipped data ------------------------------------------------------------------------------

	/**
	 * Shipped obsidian: 1.5 s hold removes the block with no drop or XP, gives trait I + weakness I whose attributes
	 * apply (blast knockback resistance up, speed down), the 10 s cooldown refuses the next try, re-absorbing gives
	 * II and III, and a fourth absorb at III is refused with nothing consumed.
	 */
	@GameTest
	public void shippedObsidianStacksToThreeWithoutDrops(GameTestHelper h) {
		SourceDefinition obsidian = shipped(h, "obsidian");
		ServerPlayer p = player(h, FEET);
		try {
			double speedBefore = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
			AbsorbTarget target = AbsorbTarget.block(h.absolutePos(TARGET));
			p.lookAt(EntityAnchorArgument.Anchor.EYES, h.absoluteVec(Vec3.atCenterOf(TARGET)));
			long t = now(h);
			for (int level = 1; level <= 3; level++) {
				h.setBlock(TARGET, Blocks.OBSIDIAN);
				t = absorb(h, p, target, t, NORMAL);
				h.assertBlockPresent(Blocks.AIR, TARGET);
				assertNoDropsOrXp(h);
				TraitEntry e = entry(h, p, obsidian);
				h.assertValueEqual(e.traitLevel(), level, "trait level");
				h.assertValueEqual(e.weaknessLevel(), level, "weakness level");
				if (level == 1) {
					// cooldown: a new channel is refused until 200 ticks after completion
					h.setBlock(TARGET, Blocks.OBSIDIAN);
					AbsorbHandler.Check early = AbsorbHandler.start(p, target, t + 1);
					h.assertValueEqual(early == null ? null : early.reasonKey(), AbsorbFeedback.REFUSE_COOLDOWN, "10 s cooldown");
					AbsorbHandler.cancel(p);
				}
				t += AbsorbCaps.COOLDOWN_TICKS;
			}
			TraitEngine.recompute(p);
			h.assertTrue(p.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE) > 0.0, "trait: explosion knockback resistance");
			h.assertTrue(p.getAttributeValue(Attributes.MOVEMENT_SPEED) < speedBefore, "weakness: slower");
			h.assertTrue(p.getAttributeValue(Attributes.MOVEMENT_SPEED) >= speedBefore * 0.4 - 1e-9, "speed clamp x0.4");

			h.setBlock(TARGET, Blocks.OBSIDIAN);
			AbsorbHandler.Check maxed = AbsorbHandler.start(p, target, t);
			h.assertValueEqual(maxed == null ? null : maxed.reasonKey(), AbsorbFeedback.REFUSE_MAX_LEVEL, "refused at III");
			h.assertBlockPresent(Blocks.OBSIDIAN, TARGET);
			h.assertValueEqual(entry(h, p, obsidian).traitLevel(), 3, "still III");
			h.assertTrue(AbsorbWorldSettings.get(h.getLevel().getServer()).discovered().contains(obsidian.id()), "obsidian discovered in this world");
		} finally {
			AbsorbHandler.cancel(p);
			h.setBlock(TARGET, Blocks.AIR);
			remove(p);
		}
		h.succeed();
	}

	/** Shipped chicken: refused just above 25 %, absorbed at exactly 25 %: discarded, no drops, no XP. */
	@GameTest
	public void shippedMobAtTwentyFivePercent(GameTestHelper h) {
		SourceDefinition chicken = shipped(h, "chicken");
		ServerPlayer p = player(h, FEET);
		Mob mob = h.spawn(EntityTypes.CHICKEN, Vec3.atBottomCenterOf(TARGET));
		try {
			mob.setNoAi(true);
			p.lookAt(EntityAnchorArgument.Anchor.EYES, mob.getBoundingBox().getCenter());
			AbsorbTarget target = AbsorbTarget.entity(mob.getId());
			mob.setHealth(mob.getMaxHealth() * 0.26F);
			AbsorbHandler.Check healthy = AbsorbHandler.start(p, target, now(h));
			h.assertValueEqual(healthy == null ? null : healthy.reasonKey(), AbsorbFeedback.REFUSE_MOB_HEALTH, "26 % refused");
			AbsorbHandler.cancel(p);
			h.assertTrue(mob.isAlive(), "untouched when refused");

			mob.setHealth(mob.getMaxHealth() * AbsorbCaps.MOB_HEALTH_THRESHOLD);
			absorb(h, p, target, now(h) + 1, NORMAL);
			h.assertTrue(mob.isRemoved() && mob.getRemovalReason() == Entity.RemovalReason.DISCARDED, "consumed (discarded, not killed)");
			assertNoDropsOrXp(h);
			h.assertValueEqual(entry(h, p, chicken).traitLevel(), 1, "chicken trait I");
		} finally {
			if (!mob.isRemoved()) mob.discard();
			remove(p);
		}
		h.succeed();
	}

	/** The gamble's effect on the applied parts: pure = trait without weakness; trait mutation = trait II at once. */
	@GameTest
	public void pureAndMutationChangeWhatIsApplied(GameTestHelper h) {
		SourceDefinition obsidian = shipped(h, "obsidian");
		ServerPlayer pure = player(h, FEET);
		ServerPlayer mutated = player(h, FEET);
		try {
			double base = pure.getAttributeValue(Attributes.MOVEMENT_SPEED);
			AbsorbService.apply(pure, obsidian, MutationRoll.Outcome.PURE);
			TraitEngine.recompute(pure);
			h.assertValueEqual(entry(h, pure, obsidian).weaknessLevel(), 0, "pure: no weakness");
			h.assertTrue(pure.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE) > 0.0, "pure: trait applies");
			h.assertTrue(Math.abs(pure.getAttributeValue(Attributes.MOVEMENT_SPEED) - base) < 1e-9, "pure: weakness (slowness) not applied");

			AbsorbService.apply(mutated, obsidian, MutationRoll.Outcome.MUTATE_TRAIT);
			TraitEntry e = entry(h, mutated, obsidian);
			h.assertValueEqual(e.traitLevel(), 2, "trait doubled: I → II at once");
			h.assertValueEqual(e.weaknessLevel(), 1, "weakness I");
			h.assertTrue(e.mutated(), "mutated tag");
		} finally {
			remove(pure);
			remove(mutated);
		}
		h.succeed();
	}

	// ---- per-player, creative, spectator -----------------------------------------------------------------------

	@GameTest
	public void traitsAndCooldownArePerPlayer(GameTestHelper h) {
		SourceDefinition obsidian = shipped(h, "obsidian");
		ServerPlayer a = player(h, FEET);
		ServerPlayer b = player(h, new BlockPos(2, 1, 1));
		try {
			double speedB = b.getAttributeValue(Attributes.MOVEMENT_SPEED);
			AbsorbService.apply(a, obsidian, MutationRoll.Outcome.NORMAL);
			TraitEngine.recompute(a);
			TraitEngine.recompute(b);
			h.assertTrue(PlayerData.traits(b).get(obsidian.id()).isEmpty(), "B has no entry");
			h.assertTrue(!hasOurModifier(b, Attributes.MOVEMENT_SPEED) && b.getAttributeValue(Attributes.MOVEMENT_SPEED) == speedB, "B not slowed");
			h.assertTrue(hasOurModifier(a, Attributes.MOVEMENT_SPEED), "A slowed");
			h.assertTrue(PlayerData.runtime(a).absorbCooldownUntil > now(h), "A on cooldown");
			h.assertTrue(PlayerData.runtime(b).absorbCooldownUntil <= now(h), "B not on cooldown");
		} finally {
			remove(a);
			remove(b);
		}
		h.succeed();
	}

	/** Creative and Spectator players never feel weaknesses: shipped obsidian's slowness goes away and comes back. */
	@GameTest
	public void creativeAndSpectatorHaveNoWeaknesses(GameTestHelper h) {
		SourceDefinition obsidian = shipped(h, "obsidian");
		ServerPlayer p = player(h, FEET);
		try {
			PlayerData.setTraits(p, PlayerTraits.EMPTY.with(new TraitEntry(obsidian.id(), 3, 3, false, false)));
			TraitEngine.recompute(p);
			h.assertTrue(hasOurModifier(p, Attributes.MOVEMENT_SPEED), "survival: slowed");
			for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
				p.setGameMode(mode);
				TraitEngine.recompute(p);
				h.assertFalse(TraitEngine.isActive(p), mode + ": dormant");
				h.assertFalse(hasOurModifier(p, Attributes.MOVEMENT_SPEED), mode + ": no weakness modifier");
				h.assertFalse(hasOurModifier(p, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE), mode + ": no trait modifier either (dormant)");
			}
			p.setGameMode(GameType.SURVIVAL);
			TraitEngine.recompute(p);
			h.assertTrue(hasOurModifier(p, Attributes.MOVEMENT_SPEED), "back in survival: slowed again");
			h.assertValueEqual(entry(h, p, obsidian).traitLevel(), 3, "traits kept through mode changes");
		} finally {
			remove(p);
		}
		h.succeed();
	}

	/** Bosses: a survival player may start on a weakened Wither; a Creative (or Spectator) player never can. */
	@GameTest
	public void creativeNeverAbsorbsBosses(GameTestHelper h) {
		ServerPlayer p = player(h, FEET);
		LivingEntity wither = h.spawn(EntityTypes.WITHER, Vec3.atBottomCenterOf(TARGET));
		try {
			((Mob) wither).setNoAi(true);
			wither.setHealth(wither.getMaxHealth() * 0.2F);
			p.lookAt(EntityAnchorArgument.Anchor.EYES, wither.getBoundingBox().getCenter());
			AbsorbTarget target = AbsorbTarget.entity(wither.getId());
			long t = now(h);

			AbsorbHandler.Check survival = AbsorbHandler.validate(p, target, null, t, false);
			h.assertTrue(survival.ok(), "survival: the weakened Wither is absorbable (control), got " + survival.reasonKey());

			for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
				p.setGameMode(mode);
				p.setShiftKeyDown(true);
				AbsorbHandler.Check c = AbsorbHandler.start(p, target, ++t);
				h.assertTrue(c == null || !c.ok(), mode + ": no channel on a boss");
				h.assertTrue(PlayerData.runtime(p).channel == null, mode + ": no channel stored");
				for (int i = 0; i < AbsorbCaps.CHANNEL_TICKS + 2; i++) AbsorbHandler.tick(p, ++t, NORMAL);
				h.assertTrue(wither.isAlive() && !wither.isRemoved(), mode + ": boss untouched");
				h.assertTrue(PlayerData.traits(p).entries().isEmpty(), mode + ": no traits gained");
				AbsorbHandler.cancel(p);
			}
		} finally {
			AbsorbHandler.cancel(p);
			if (!wither.isRemoved()) wither.discard();
			remove(p);
		}
		h.succeed();
	}

	/** A channel started in survival is cancelled the moment the player switches to Creative (nothing consumed). */
	@GameTest
	public void switchingToCreativeMidChannelCancels(GameTestHelper h) {
		ServerPlayer p = player(h, FEET);
		try {
			h.setBlock(TARGET, Blocks.OBSIDIAN);
			p.lookAt(EntityAnchorArgument.Anchor.EYES, h.absoluteVec(Vec3.atCenterOf(TARGET)));
			AbsorbTarget target = AbsorbTarget.block(h.absolutePos(TARGET));
			long t = now(h);
			AbsorbHandler.Check c = AbsorbHandler.start(p, target, t);
			h.assertTrue(c != null && c.ok(), "survival starts");
			p.setGameMode(GameType.CREATIVE);
			AbsorbHandler.TickResult r = null;
			for (int i = 1; i <= AbsorbCaps.CHANNEL_TICKS; i++) {
				AbsorbHandler.start(p, target, t + i);
				r = AbsorbHandler.tick(p, t + i, NORMAL);
				if (r.status() != ChannelStatePayload.Status.PROGRESS) break;
			}
			h.assertTrue(r != null && r.status() == ChannelStatePayload.Status.CANCELLED, "cancelled in creative, got " + (r == null ? null : r.status()));
			h.assertBlockPresent(Blocks.OBSIDIAN, TARGET);
			h.assertTrue(PlayerData.traits(p).entries().isEmpty(), "no traits");
		} finally {
			AbsorbHandler.cancel(p);
			h.setBlock(TARGET, Blocks.AIR);
			remove(p);
		}
		h.succeed();
	}

	/** Shipped sources whose id is valid still round-trip through the matcher by id (datapack ids are namespace:path). */
	@GameTest
	public void sourceIdsAreNamespacePath(GameTestHelper h) {
		for (SourceDefinition s : SourceRegistry.all()) {
			Identifier id = s.id();
			h.assertTrue(SourceRegistry.byId(id).isPresent(), "byId " + id);
		}
		h.succeed();
	}
}
