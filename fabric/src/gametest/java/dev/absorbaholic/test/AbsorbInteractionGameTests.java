package dev.absorbaholic.test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.mojang.authlib.GameProfile;
import dev.absorbaholic.absorb.AbsorbFeedback;
import dev.absorbaholic.absorb.AbsorbHandler;
import dev.absorbaholic.absorb.AbsorbRules;
import dev.absorbaholic.absorb.AbsorbService;
import dev.absorbaholic.absorb.AbsorbTarget;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.core.MutationRoll;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.net.AbsorbedPayload;
import dev.absorbaholic.net.ChannelStatePayload;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.registry.SourceTargets;
import dev.absorbaholic.world.AbsorbWorldSettings;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * WP-ABSORB. Server gametests of the absorb interaction: channel validation, consumption (no drops / XP / container
 * spill), rolls, cooldown, refusals, eviction, feedback and the discovered set. Most tests drive the channel
 * synchronously with simulated game times ({@code AbsorbHandler.start / tick(player, now)}) so the global source
 * registry and world settings they swap are restored before the tick ends; {@link #channelCompletesOnServerTicks}
 * runs one channel through the real END_SERVER_TICK wiring.
 */
public class AbsorbInteractionGameTests {
	/** The mock player's feet (relative); it looks at {@link #TARGET} two blocks ahead. */
	private static final BlockPos FEET = new BlockPos(1, 1, 1);
	private static final BlockPos TARGET = new BlockPos(1, 1, 3);
	private static final MutationRoll.Uniform NORMAL_ROLL = () -> 0.5;

	/** A mock player whose outbound packets can be inspected. */
	private record Mock(ServerPlayer player, EmbeddedChannel channel) {
		List<Object> drain() {
			channel.runPendingTasks();
			List<Object> out = new ArrayList<>(channel.outboundMessages());
			channel.outboundMessages().clear();
			return out;
		}
	}

	// ---- helpers -----------------------------------------------------------------------------------------------

	/** Survival mock player (as TestSupport.survivalPlayer, keeping its channel), sneaking at FEET, empty-handed. */
	private static Mock mock(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "absorb-mock"), false);
		ServerPlayer p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		p.setGameMode(GameType.SURVIVAL);
		p.getInventory().clearContent();
		Vec3 feet = h.absoluteVec(Vec3.atBottomCenterOf(FEET));
		p.snapTo(feet.x, feet.y, feet.z, 0.0F, 0.0F);
		p.setShiftKeyDown(true);
		Mock mock = new Mock(p, channel);
		mock.drain();
		return mock;
	}

	private static void lookAt(ServerPlayer p, Vec3 absolute) {
		p.lookAt(EntityAnchorArgument.Anchor.EYES, absolute);
	}

	private static void lookAtBlock(GameTestHelper h, ServerPlayer p, BlockPos relative) {
		lookAt(p, h.absoluteVec(Vec3.atCenterOf(relative)));
	}

	private static SourceDefinition blockSource(String path, Block block, int maxLevel) {
		return TestSupport.blockSource(path, BuiltInRegistries.BLOCK.getKey(block), maxLevel, List.of(), List.of(), List.of(), List.of());
	}

	private static SourceDefinition entitySource(String path, Identifier entityType, int maxLevel) {
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", path),
				new SourceTargets(SourceKind.ENTITY, List.of(entityType), List.of()), Optional.empty(), 0xFFFFFF, Tier.COMMON, maxLevel,
				new SourceDefinition.Side("test_trait", List.of(), List.of()),
				new SourceDefinition.Side("test_weakness", List.of(), List.of()));
	}

	/** Runs {@code body} with exactly {@code sources} registered, mode ON; restores the previous sources. */
	private static void withSources(GameTestHelper h, List<SourceDefinition> sources, Runnable body) {
		List<SourceDefinition> saved = SourceRegistry.all();
		TestSupport.setMode(h, true);
		SourceRegistry.set(sources);
		try {
			body.run();
		} finally {
			SourceRegistry.set(saved);
		}
	}

	private static long now(GameTestHelper h) {
		return AbsorbHandler.now(h.getLevel().getServer());
	}

	/** Starts a channel on {@code target} at {@code start} (asserting it starts). */
	private static void begin(GameTestHelper h, ServerPlayer p, AbsorbTarget target, long start) {
		AbsorbHandler.Check check = AbsorbHandler.start(p, target, start);
		h.assertTrue(check != null && check.ok(), "channel should start, got " + describe(check));
		h.assertTrue(PlayerData.runtime(p).channel != null, "channel stored");
	}

	/** Heartbeats and ticks ticks start+1 .. start+ticks; stops early at a non-PROGRESS result. */
	private static AbsorbHandler.TickResult hold(ServerPlayer p, AbsorbTarget target, long start, int ticks, MutationRoll.Uniform roll) {
		AbsorbHandler.TickResult result = null;
		for (int i = 1; i <= ticks; i++) {
			AbsorbHandler.start(p, target, start + i);
			result = AbsorbHandler.tick(p, start + i, roll);
			if (result.status() != ChannelStatePayload.Status.PROGRESS) return result;
		}
		return result;
	}

	/** Starts and holds a full channel; asserts COMPLETED exactly at CHANNEL_TICKS. Returns the completion time. */
	private static long absorbFully(GameTestHelper h, ServerPlayer p, AbsorbTarget target, MutationRoll.Uniform roll) {
		return absorbFully(h, p, target, roll, now(h));
	}

	/** As above, starting at the simulated game time {@code start}. */
	private static long absorbFully(GameTestHelper h, ServerPlayer p, AbsorbTarget target, MutationRoll.Uniform roll, long start) {
		begin(h, p, target, start);
		AbsorbHandler.TickResult before = hold(p, target, start, AbsorbCaps.CHANNEL_TICKS - 1, roll);
		h.assertValueEqual(before.status(), ChannelStatePayload.Status.PROGRESS, "still channeling one tick before the end");
		AbsorbHandler.start(p, target, start + AbsorbCaps.CHANNEL_TICKS);
		AbsorbHandler.TickResult done = AbsorbHandler.tick(p, start + AbsorbCaps.CHANNEL_TICKS, roll);
		h.assertValueEqual(done.status(), ChannelStatePayload.Status.COMPLETED, "completed at CHANNEL_TICKS");
		h.assertTrue(PlayerData.runtime(p).channel == null, "channel cleared after completion");
		return start + AbsorbCaps.CHANNEL_TICKS;
	}

	private static String describe(AbsorbHandler.Check check) {
		return check == null ? "null" : check.ok() ? "ok" : "refused " + check.reasonKey();
	}

	private static void assertNoDropsOrXp(GameTestHelper h) {
		AABB area = h.getBounds().inflate(3.0);
		h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "no item drops");
		h.assertTrue(h.getLevel().getEntitiesOfClass(ExperienceOrb.class, area).isEmpty(), "no experience orbs");
	}

	private static TraitEntry entry(GameTestHelper h, ServerPlayer p, SourceDefinition source) {
		Optional<TraitEntry> e = PlayerData.traits(p).get(source.id());
		h.assertTrue(e.isPresent(), "trait entry for " + source.id());
		return e.get();
	}

	private static void assertEntry(GameTestHelper h, ServerPlayer p, SourceDefinition source, int trait, int weakness) {
		TraitEntry e = entry(h, p, source);
		h.assertValueEqual(e.traitLevel(), trait, "trait level of " + source.id());
		h.assertValueEqual(e.weaknessLevel(), weakness, "weakness level of " + source.id());
	}

	private static String key(Component c) {
		return c.getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
	}

	/** Deterministic roll: returns the given values in order. */
	private static MutationRoll.Uniform rolls(double... values) {
		Iterator<Double> it = java.util.Arrays.stream(values).boxed().iterator();
		return it::next;
	}

	/** A channel started on an absorbable block at TARGET and held for 3 ticks. */
	private record Started(Mock mock, AbsorbTarget target, long start) {}

	private static Started startedBlockChannel(GameTestHelper h, SourceDefinition source) {
		h.setBlock(TARGET, Blocks.AMETHYST_BLOCK);
		Mock m = mock(h);
		lookAtBlock(h, m.player(), TARGET);
		AbsorbTarget target = AbsorbTarget.block(h.absolutePos(TARGET));
		long start = now(h);
		begin(h, m.player(), target, start);
		h.assertValueEqual(hold(m.player(), target, start, 3, NORMAL_ROLL).status(), ChannelStatePayload.Status.PROGRESS, "valid channel continues");
		return new Started(m, target, start);
	}

	/** One more heartbeat + tick after a change: the channel must be cancelled and nothing consumed. */
	private static AbsorbHandler.TickResult assertCancelled(GameTestHelper h, Started s, boolean heartbeat) {
		long t = s.start() + 4;
		if (heartbeat) AbsorbHandler.start(s.mock().player(), s.target(), t);
		AbsorbHandler.TickResult r = AbsorbHandler.tick(s.mock().player(), t, NORMAL_ROLL);
		h.assertValueEqual(r.status(), ChannelStatePayload.Status.CANCELLED, "channel cancelled");
		h.assertTrue(PlayerData.runtime(s.mock().player()).channel == null, "channel cleared");
		h.assertTrue(PlayerData.traits(s.mock().player()).isEmpty(), "nothing absorbed");
		return r;
	}

	// ---- consumption -------------------------------------------------------------------------------------------

	@GameTest
	public void blockAbsorbedWithoutDropsOrXp(GameTestHelper h) {
		// unique per run: the discovered set persists in the (reused) gametest world
		SourceDefinition source = blockSource("absorb_block_" + UUID.randomUUID().toString().replace("-", ""), Blocks.DIAMOND_ORE, 3);
		withSources(h, List.of(source), () -> {
			h.setBlock(TARGET, Blocks.DIAMOND_ORE);
			Mock m = mock(h);
			ServerPlayer p = m.player();
			lookAtBlock(h, p, TARGET);
			h.assertFalse(AbsorbWorldSettings.get(h.getLevel().getServer()).isDiscovered(source.id()), "not discovered before");
			long end = absorbFully(h, p, AbsorbTarget.block(h.absolutePos(TARGET)), NORMAL_ROLL);
			h.assertBlockPresent(Blocks.AIR, TARGET);
			assertNoDropsOrXp(h);
			assertEntry(h, p, source, 1, 1);
			h.assertValueEqual(PlayerData.runtime(p).absorbCooldownUntil, end + AbsorbCaps.COOLDOWN_TICKS, "cooldown started");
			h.assertTrue(AbsorbWorldSettings.get(h.getLevel().getServer()).isDiscovered(source.id()), "source discovered");
		});
		h.succeed();
	}

	@GameTest
	public void doorRemovedWholeWithoutDrops(GameTestHelper h) {
		SourceDefinition source = blockSource("absorb_door", Blocks.OAK_DOOR, 3);
		withSources(h, List.of(source), () -> {
			BlockState lower = Blocks.OAK_DOOR.defaultBlockState();
			h.setBlock(TARGET, lower);
			h.setBlock(TARGET.above(), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
			Mock m = mock(h);
			lookAtBlock(h, m.player(), TARGET.above());
			absorbFully(h, m.player(), AbsorbTarget.block(h.absolutePos(TARGET.above())), NORMAL_ROLL);
			h.assertBlockPresent(Blocks.AIR, TARGET);
			h.assertBlockPresent(Blocks.AIR, TARGET.above());
			assertNoDropsOrXp(h);
			assertEntry(h, m.player(), source, 1, 1);
		});
		h.succeed();
	}

	@GameTest
	public void lavaSourceAbsorbed(GameTestHelper h) {
		SourceDefinition source = blockSource("absorb_lava", Blocks.LAVA, 3);
		withSources(h, List.of(source), () -> {
			Mock m = mock(h);
			ServerPlayer p = m.player();
			long t = now(h);
			// flowing lava is not a source
			h.setBlock(TARGET, Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 2));
			lookAtBlock(h, p, TARGET);
			AbsorbTarget fluid = AbsorbTarget.fluid(h.absolutePos(TARGET));
			h.assertFalse(AbsorbHandler.start(p, fluid, t).ok(), "flowing lava refused");
			AbsorbHandler.cancel(p);

			h.setBlock(TARGET, Blocks.LAVA);
			h.assertFalse(AbsorbHandler.start(p, AbsorbTarget.block(h.absolutePos(TARGET)), t + 1).ok(), "lava is a FLUID target, not a BLOCK");
			AbsorbHandler.cancel(p);
			absorbFully(h, p, fluid, NORMAL_ROLL, t + 2);
			h.assertBlockPresent(Blocks.AIR, TARGET);
			h.assertTrue(h.getLevel().getFluidState(h.absolutePos(TARGET)).isEmpty(), "no fluid left");
			assertEntry(h, p, source, 1, 1);
		});
		h.succeed();
	}

	@GameTest
	public void weakMobDiscardedWithoutLoot(GameTestHelper h) {
		SourceDefinition source = entitySource("absorb_pig", BuiltInRegistries.ENTITY_TYPE.getKey(EntityTypes.PIG), 3);
		withSources(h, List.of(source), () -> {
			Mob pig = h.spawn(EntityTypes.PIG, Vec3.atBottomCenterOf(TARGET));
			pig.setNoAi(true);
			pig.setHealth(pig.getMaxHealth() * AbsorbCaps.MOB_HEALTH_THRESHOLD); // exactly 25 %: allowed
			Mock m = mock(h);
			lookAt(m.player(), pig.getBoundingBox().getCenter());
			absorbFully(h, m.player(), AbsorbTarget.entity(pig.getId()), NORMAL_ROLL);
			h.assertTrue(pig.isRemoved() && pig.getRemovalReason() == Entity.RemovalReason.DISCARDED, "pig discarded");
			assertNoDropsOrXp(h);
			assertEntry(h, m.player(), source, 1, 1);
		});
		h.succeed();
	}

	@GameTest
	public void healthyMobRefused(GameTestHelper h) {
		SourceDefinition source = entitySource("absorb_healthy_pig", BuiltInRegistries.ENTITY_TYPE.getKey(EntityTypes.PIG), 3);
		withSources(h, List.of(source), () -> {
			Mob pig = h.spawn(EntityTypes.PIG, Vec3.atBottomCenterOf(TARGET));
			pig.setNoAi(true);
			pig.setHealth(pig.getMaxHealth() * AbsorbCaps.MOB_HEALTH_THRESHOLD + 0.5F);
			Mock m = mock(h);
			lookAt(m.player(), pig.getBoundingBox().getCenter());
			AbsorbHandler.Check check = AbsorbHandler.start(m.player(), AbsorbTarget.entity(pig.getId()), now(h));
			h.assertValueEqual(check.reasonKey(), AbsorbFeedback.REFUSE_MOB_HEALTH, "refusal reason");
			h.assertTrue(PlayerData.runtime(m.player()).channel == null, "no channel");
			h.assertTrue(pig.isAlive() && !pig.isRemoved(), "pig untouched");
			boolean refusalShown = m.drain().stream().anyMatch(o -> o instanceof ClientboundSystemChatPacket c && c.overlay()
					&& key(c.content()).equals(AbsorbFeedback.REFUSE_MOB_HEALTH));
			h.assertTrue(refusalShown, "refusal on the actionbar");
		});
		h.succeed();
	}

	@GameTest
	public void fullContainerRefused(GameTestHelper h) {
		SourceDefinition source = blockSource("absorb_barrel", Blocks.BARREL, 3);
		withSources(h, List.of(source), () -> {
			h.setBlock(TARGET, Blocks.BARREL);
			Container barrel = (Container) h.getLevel().getBlockEntity(h.absolutePos(TARGET));
			barrel.setItem(0, new ItemStack(Items.DIAMOND));
			Mock m = mock(h);
			ServerPlayer p = m.player();
			lookAtBlock(h, p, TARGET);
			AbsorbTarget target = AbsorbTarget.block(h.absolutePos(TARGET));
			AbsorbHandler.Check check = AbsorbHandler.start(p, target, now(h));
			h.assertValueEqual(check.reasonKey(), AbsorbFeedback.REFUSE_CONTAINER, "refusal reason");
			h.assertBlockPresent(Blocks.BARREL, TARGET);

			barrel.clearContent();
			AbsorbHandler.cancel(p);
			absorbFully(h, p, target, NORMAL_ROLL, now(h) + 1);
			h.assertBlockPresent(Blocks.AIR, TARGET);
			assertNoDropsOrXp(h);
		});
		h.succeed();
	}

	// ---- cooldown, max level, eviction -------------------------------------------------------------------------

	@GameTest
	public void cooldownBlocksNextAbsorption(GameTestHelper h) {
		SourceDefinition source = blockSource("absorb_cooldown", Blocks.AMETHYST_BLOCK, 3);
		withSources(h, List.of(source), () -> {
			h.setBlock(TARGET, Blocks.AMETHYST_BLOCK);
			Mock m = mock(h);
			ServerPlayer p = m.player();
			lookAtBlock(h, p, TARGET);
			AbsorbTarget target = AbsorbTarget.block(h.absolutePos(TARGET));
			long end = absorbFully(h, p, target, NORMAL_ROLL);
			h.setBlock(TARGET, Blocks.AMETHYST_BLOCK);
			h.assertTrue(AbsorbHandler.start(p, target, end + 1) == null, "the finished gesture is ignored until release");
			AbsorbHandler.cancel(p);
			AbsorbHandler.Check early = AbsorbHandler.start(p, target, end + 2);
			h.assertValueEqual(early.reasonKey(), AbsorbFeedback.REFUSE_COOLDOWN, "refused during cooldown");
			AbsorbHandler.cancel(p);
			AbsorbHandler.Check late = AbsorbHandler.start(p, target, end + AbsorbCaps.COOLDOWN_TICKS - 1);
			h.assertValueEqual(late.reasonKey(), AbsorbFeedback.REFUSE_COOLDOWN, "still refused one tick before the end");
			AbsorbHandler.cancel(p);
			begin(h, p, target, end + AbsorbCaps.COOLDOWN_TICKS);
			AbsorbHandler.cancel(p);
		});
		h.succeed();
	}

	@GameTest
	public void maxLevelRefusalConsumesNothing(GameTestHelper h) {
		SourceDefinition source = blockSource("absorb_max", Blocks.AMETHYST_BLOCK, 2);
		withSources(h, List.of(source), () -> {
			h.setBlock(TARGET, Blocks.AMETHYST_BLOCK);
			Mock m = mock(h);
			ServerPlayer p = m.player();
			PlayerTraits maxed = PlayerTraits.EMPTY.with(new TraitEntry(source.id(), 2, 1, false, false));
			PlayerData.setTraits(p, maxed);
			lookAtBlock(h, p, TARGET);
			AbsorbTarget target = AbsorbTarget.block(h.absolutePos(TARGET));
			AbsorbHandler.Check check = AbsorbHandler.start(p, target, now(h));
			h.assertValueEqual(check.reasonKey(), AbsorbFeedback.REFUSE_MAX_LEVEL, "refusal reason");
			h.assertTrue(PlayerData.runtime(p).channel == null, "no channel");

			// the completion path re-checks and consumes nothing either
			AbsorbHandler.Resolved resolved = new AbsorbHandler.Resolved(target, source, Blocks.AMETHYST_BLOCK.getName(), null);
			AbsorbService.Result result = AbsorbService.complete(p, resolved, MutationRoll.Outcome.MUTATE_TRAIT, now(h));
			h.assertFalse(result.success(), "complete refused");
			h.assertFalse(AbsorbService.apply(p, source, MutationRoll.Outcome.NORMAL).success(), "apply refused");
			h.assertBlockPresent(Blocks.AMETHYST_BLOCK, TARGET);
			h.assertValueEqual(PlayerData.traits(p), maxed, "traits unchanged");
			h.assertValueEqual(PlayerData.runtime(p).absorbCooldownUntil, 0L, "no cooldown");
		});
		h.succeed();
	}

	@GameTest
	public void levelsStackUpToMax(GameTestHelper h) {
		SourceDefinition source = blockSource("absorb_stack", Blocks.AMETHYST_BLOCK, 3);
		withSources(h, List.of(source), () -> {
			ServerPlayer p = mock(h).player();
			AbsorbService.Result first = AbsorbService.apply(p, source, MutationRoll.Outcome.NORMAL);
			h.assertValueEqual(first.traitLevel(), 1, "first trait level");
			AbsorbService.Result second = AbsorbService.apply(p, source, MutationRoll.Outcome.MUTATE_WEAKNESS);
			h.assertValueEqual(second.traitLevel(), 2, "second trait level");
			h.assertValueEqual(second.weaknessLevel(), 3, "second weakness level");
			AbsorbService.Result third = AbsorbService.apply(p, source, MutationRoll.Outcome.MUTATE_TRAIT);
			h.assertValueEqual(third.traitLevel(), 3, "trait capped at max");
			h.assertValueEqual(third.weaknessLevel(), 3, "weakness capped at max");
			h.assertTrue(entry(h, p, source).mutated(), "mutated tag sticks");
			h.assertFalse(AbsorbService.apply(p, source, MutationRoll.Outcome.NORMAL).success(), "refused at max");
		});
		h.succeed();
	}

	@GameTest
	public void oldestEvictedAtCap(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		SourceDefinition a = blockSource("evict_a", Blocks.AMETHYST_BLOCK, 3);
		SourceDefinition b = blockSource("evict_b", Blocks.CALCITE, 3);
		SourceDefinition c = blockSource("evict_c", Blocks.TUFF, 3);
		int savedMax = AbsorbWorldSettings.get(server).maxTraits();
		AbsorbWorldSettings.setMaxTraits(server, 2);
		try {
			withSources(h, List.of(a, b, c), () -> {
				Mock m = mock(h);
				ServerPlayer p = m.player();
				AbsorbService.apply(p, a, MutationRoll.Outcome.NORMAL);
				AbsorbService.apply(p, b, MutationRoll.Outcome.NORMAL);
				AbsorbService.Result again = AbsorbService.apply(p, b, MutationRoll.Outcome.NORMAL);
				h.assertTrue(again.evicted().isEmpty(), "re-absorbing a held source evicts nothing");

				AbsorbService.Result result = AbsorbService.apply(p, c, MutationRoll.Outcome.NORMAL);
				h.assertValueEqual(result.evicted(), List.of(a.id()), "oldest evicted");
				List<Identifier> order = PlayerData.traits(p).entries().stream().map(TraitEntry::source).toList();
				h.assertValueEqual(order, List.of(b.id(), c.id()), "entries after eviction");
				assertEntry(h, p, b, 2, 2);

				// the notice reaches the player (vanilla path: a chat line)
				m.drain();
				AbsorbFeedback.absorbed(p, Blocks.TUFF.getName(), c, result, false);
				boolean notice = m.drain().stream().anyMatch(o -> o instanceof ClientboundSystemChatPacket chat && !chat.overlay()
						&& key(chat.content()).equals(AbsorbFeedback.EVICTED));
				h.assertTrue(notice, "eviction notice in chat");
			});
		} finally {
			AbsorbWorldSettings.setMaxTraits(server, savedMax);
		}
		h.succeed();
	}

	// ---- validation failures cancel the channel ----------------------------------------------------------------

	@GameTest
	public void cancelsWhenTargetChanges(GameTestHelper h) {
		withSources(h, List.of(blockSource("cancel_target", Blocks.AMETHYST_BLOCK, 3)), () -> {
			Started s = startedBlockChannel(h, SourceRegistry.all().getFirst());
			h.setBlock(TARGET, Blocks.STONE); // no source any more
			AbsorbHandler.TickResult r = assertCancelled(h, s, true);
			h.assertTrue(r.reasonKey() == null, "target loss is silent");
			h.assertTrue(AbsorbHandler.start(s.mock().player(), s.target(), s.start() + 5) == null, "no restart while held");
		});
		h.succeed();
	}

	@GameTest
	public void cancelsWhenMobMovesAway(GameTestHelper h) {
		SourceDefinition source = entitySource("cancel_mob", BuiltInRegistries.ENTITY_TYPE.getKey(EntityTypes.PIG), 3);
		withSources(h, List.of(source), () -> {
			Mob pig = h.spawn(EntityTypes.PIG, Vec3.atBottomCenterOf(TARGET));
			pig.setNoAi(true);
			pig.setHealth(1.0F);
			Mock m = mock(h);
			lookAt(m.player(), pig.getBoundingBox().getCenter());
			AbsorbTarget target = AbsorbTarget.entity(pig.getId());
			long start = now(h);
			begin(h, m.player(), target, start);
			Vec3 away = h.absoluteVec(new Vec3(6.5, 1, 6.5));
			pig.snapTo(away.x, away.y, away.z, 0.0F, 0.0F);
			AbsorbHandler.start(m.player(), target, start + 1);
			h.assertValueEqual(AbsorbHandler.tick(m.player(), start + 1, NORMAL_ROLL).status(), ChannelStatePayload.Status.CANCELLED, "cancelled");
			h.assertTrue(pig.isAlive() && !pig.isRemoved(), "pig untouched");
		});
		h.succeed();
	}

	@GameTest
	public void cancelsWhenSneakStops(GameTestHelper h) {
		withSources(h, List.of(blockSource("cancel_sneak", Blocks.AMETHYST_BLOCK, 3)), () -> {
			Started s = startedBlockChannel(h, SourceRegistry.all().getFirst());
			s.mock().player().setShiftKeyDown(false);
			assertCancelled(h, s, true);
			h.assertBlockPresent(Blocks.AMETHYST_BLOCK, TARGET);
		});
		h.succeed();
	}

	@GameTest
	public void cancelsWithItemInHand(GameTestHelper h) {
		withSources(h, List.of(blockSource("cancel_item", Blocks.AMETHYST_BLOCK, 3)), () -> {
			Started s = startedBlockChannel(h, SourceRegistry.all().getFirst());
			ServerPlayer p = s.mock().player();
			p.getInventory().setItem(p.getInventory().getSelectedSlot(), new ItemStack(Items.STICK));
			h.assertFalse(AbsorbRules.poseAllows(p), "pose no longer allows");
			assertCancelled(h, s, true);
			h.assertBlockPresent(Blocks.AMETHYST_BLOCK, TARGET);
		});
		h.succeed();
	}

	@GameTest
	public void cancelsOutOfRange(GameTestHelper h) {
		withSources(h, List.of(blockSource("cancel_range", Blocks.AMETHYST_BLOCK, 3)), () -> {
			Started s = startedBlockChannel(h, SourceRegistry.all().getFirst());
			ServerPlayer p = s.mock().player();
			Vec3 far = h.absoluteVec(new Vec3(1.5, 1, -8.5));
			p.snapTo(far.x, far.y, far.z, 0.0F, 0.0F);
			lookAtBlock(h, p, TARGET); // still looking at it, but too far
			assertCancelled(h, s, true);
			h.assertBlockPresent(Blocks.AMETHYST_BLOCK, TARGET);
		});
		h.succeed();
	}

	@GameTest
	public void cancelsWhenLookingAway(GameTestHelper h) {
		withSources(h, List.of(blockSource("cancel_look", Blocks.AMETHYST_BLOCK, 3)), () -> {
			Started s = startedBlockChannel(h, SourceRegistry.all().getFirst());
			lookAt(s.mock().player(), h.absoluteVec(new Vec3(6.5, 2.5, 1.5)));
			assertCancelled(h, s, true);
		});
		h.succeed();
	}

	@GameTest
	public void cancelsWithoutHeartbeat(GameTestHelper h) {
		withSources(h, List.of(blockSource("cancel_heartbeat", Blocks.AMETHYST_BLOCK, 3)), () -> {
			Started s = startedBlockChannel(h, SourceRegistry.all().getFirst());
			ServerPlayer p = s.mock().player();
			long lastBeat = s.start() + 3;
			h.assertValueEqual(AbsorbHandler.tick(p, lastBeat + 5, NORMAL_ROLL).status(), ChannelStatePayload.Status.PROGRESS,
					"a 5-tick gap is tolerated");
			AbsorbHandler.TickResult r = AbsorbHandler.tick(p, lastBeat + 6, NORMAL_ROLL);
			h.assertValueEqual(r.status(), ChannelStatePayload.Status.CANCELLED, "timed out");
			h.assertTrue(PlayerData.runtime(p).channel == null, "channel cleared");
		});
		h.succeed();
	}

	@GameTest
	public void releaseCancels(GameTestHelper h) {
		withSources(h, List.of(blockSource("cancel_release", Blocks.AMETHYST_BLOCK, 3)), () -> {
			Started s = startedBlockChannel(h, SourceRegistry.all().getFirst());
			AbsorbHandler.cancel(s.mock().player());
			h.assertTrue(PlayerData.runtime(s.mock().player()).channel == null, "channel cleared on release");
			// a new press on the same target starts again
			begin(h, s.mock().player(), s.target(), s.start() + 5);
			AbsorbHandler.cancel(s.mock().player());
		});
		h.succeed();
	}

	@GameTest
	public void cancelsWhenModeTurnsOff(GameTestHelper h) {
		withSources(h, List.of(blockSource("cancel_mode", Blocks.AMETHYST_BLOCK, 3)), () -> {
			Started s = startedBlockChannel(h, SourceRegistry.all().getFirst());
			TestSupport.setMode(h, false);
			try {
				AbsorbHandler.TickResult r = assertCancelled(h, s, true);
				h.assertValueEqual(r.reasonKey(), AbsorbFeedback.REFUSE_DISABLED, "reason");
				AbsorbHandler.cancel(s.mock().player());
				h.assertValueEqual(AbsorbHandler.start(s.mock().player(), s.target(), s.start() + 5).reasonKey(),
						AbsorbFeedback.REFUSE_DISABLED, "cannot start while OFF");
			} finally {
				TestSupport.setMode(h, true);
			}
			h.assertBlockPresent(Blocks.AMETHYST_BLOCK, TARGET);
		});
		h.succeed();
	}

	@GameTest
	public void creativeAndSpectatorNeverAbsorb(GameTestHelper h) {
		withSources(h, List.of(blockSource("cancel_creative", Blocks.AMETHYST_BLOCK, 3)), () -> {
			Started s = startedBlockChannel(h, SourceRegistry.all().getFirst());
			ServerPlayer p = s.mock().player();
			p.setGameMode(GameType.CREATIVE);
			assertCancelled(h, s, true);
			AbsorbHandler.cancel(p);
			h.assertFalse(AbsorbHandler.start(p, s.target(), s.start() + 10).ok(), "creative cannot start");
			AbsorbHandler.cancel(p);
			p.setGameMode(GameType.SPECTATOR);
			h.assertFalse(AbsorbHandler.start(p, s.target(), s.start() + 20).ok(), "spectator cannot start");
			AbsorbHandler.cancel(p);
			p.setGameMode(GameType.ADVENTURE);
			p.setShiftKeyDown(true);
			begin(h, p, s.target(), s.start() + 30); // adventure is allowed
			AbsorbHandler.cancel(p);
			h.assertBlockPresent(Blocks.AMETHYST_BLOCK, TARGET);
		});
		h.succeed();
	}

	@GameTest
	public void bedrockProtectedNearWorldFloor(GameTestHelper h) {
		SourceDefinition source = blockSource("absorb_bedrock", Blocks.BEDROCK, 3);
		ServerLevel level = h.getLevel();
		// the test area floats high above the floor: carve a small pocket into the ground below it (same chunk
		// column, so it is loaded) and restore every touched block afterwards
		int protectedY = level.dimensionType().minY() + AbsorbCaps.PROTECTED_LAYERS - 1; // top protected layer
		BlockPos base = h.absolutePos(FEET);
		BlockPos feet = new BlockPos(base.getX(), protectedY, base.getZ());
		BlockPos low = feet.south(2);
		BlockPos high = low.above();
		List<BlockPos> touched = new ArrayList<>();
		for (int dy = 0; dy <= 2; dy++) {
			touched.add(feet.above(dy));
			touched.add(feet.south().above(dy));
			touched.add(low.above(dy));
		}
		List<BlockState> saved = touched.stream().map(level::getBlockState).toList();
		try {
			withSources(h, List.of(source), () -> {
				for (BlockPos pos : touched) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
				level.setBlockAndUpdate(low, Blocks.BEDROCK.defaultBlockState());
				Mock m = mock(h);
				ServerPlayer p = m.player();
				p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
				lookAt(p, Vec3.atCenterOf(low));
				h.assertTrue(AbsorbRules.isProtected(level, low, Blocks.BEDROCK.defaultBlockState()), "top protected layer");
				h.assertValueEqual(AbsorbHandler.start(p, AbsorbTarget.block(low), now(h)).reasonKey(),
						AbsorbFeedback.REFUSE_PROTECTED, "bottom-layer bedrock is protected");
				AbsorbHandler.cancel(p);
				level.setBlockAndUpdate(high, Blocks.BEDROCK.defaultBlockState());
				lookAt(p, Vec3.atCenterOf(high));
				begin(h, p, AbsorbTarget.block(high), now(h) + 1); // one layer higher it is ordinary
				AbsorbHandler.cancel(p);
			});
		} finally {
			for (int i = 0; i < touched.size(); i++) level.setBlockAndUpdate(touched.get(i), saved.get(i));
		}
		h.succeed();
	}

	// ---- outcomes and feedback ---------------------------------------------------------------------------------

	@GameTest
	public void injectedRollsDecideOutcome(GameTestHelper h) {
		SourceDefinition mutate = blockSource("roll_mutate", Blocks.AMETHYST_BLOCK, 3);
		SourceDefinition pure = blockSource("roll_pure", Blocks.CALCITE, 3);
		SourceDefinition trait = blockSource("roll_trait", Blocks.TUFF, 3);
		withSources(h, List.of(mutate, pure, trait), () -> {
			h.setBlock(TARGET, Blocks.AMETHYST_BLOCK);
			Mock m = mock(h);
			ServerPlayer p = m.player();
			lookAtBlock(h, p, TARGET);
			// full channel: r = 0.1 (mutation), coin = 0.9 (weakness)
			absorbFully(h, p, AbsorbTarget.block(h.absolutePos(TARGET)), rolls(0.1, 0.9));
			assertEntry(h, p, mutate, 1, 2);
			h.assertTrue(entry(h, p, mutate).mutated(), "mutated tag");
			List<Object> out = m.drain();
			h.assertTrue(out.stream().anyMatch(o -> o instanceof ClientboundSystemChatPacket c && !c.overlay()
					&& key(c.content()).equals("absorbaholic.announce.mutate_weakness")), "mutation broadcast in chat");
			h.assertTrue(out.stream().anyMatch(o -> o instanceof ClientboundSetSubtitleTextPacket s
					&& s.text().getSiblings().stream().anyMatch(c -> key(c).equals("absorbaholic.absorbed.subtitle.mutate_weakness"))),
					"special subtitle line");

			// pure: r = 0.01; trait mutation: r = 0.1, coin = 0.2 (via the completion path)
			PlayerData.runtime(p).absorbCooldownUntil = 0;
			AbsorbService.Result pr = AbsorbService.complete(p, resolved(h, pure, Blocks.CALCITE, new BlockPos(2, 1, 3)),
					MutationRoll.roll(rolls(0.01)), now(h));
			h.assertValueEqual(pr.outcome(), MutationRoll.Outcome.PURE, "pure outcome");
			assertEntry(h, p, pure, 1, 0);
			h.assertTrue(entry(h, p, pure).pure(), "pure tag");
			h.assertValueEqual(key(AbsorbFeedback.actionbar(pure, 1, 0)), "absorbaholic.absorbed.actionbar.pure", "pure actionbar");
			h.assertTrue(m.drain().stream().anyMatch(o -> o instanceof ClientboundSystemChatPacket c && !c.overlay()
					&& key(c.content()).equals("absorbaholic.announce.pure")), "pure broadcast");

			AbsorbService.Result tr = AbsorbService.complete(p, resolved(h, trait, Blocks.TUFF, new BlockPos(3, 1, 3)),
					MutationRoll.roll(rolls(0.1, 0.2)), now(h));
			h.assertValueEqual(tr.outcome(), MutationRoll.Outcome.MUTATE_TRAIT, "trait mutation outcome");
			assertEntry(h, p, trait, 2, 1);
			h.assertValueEqual(MutationRoll.roll(rolls(0.5)), MutationRoll.Outcome.NORMAL, "normal outcome");
		});
		h.succeed();
	}

	/** Places {@code block} at {@code relative} and returns it as a resolved target (for direct completion). */
	private static AbsorbHandler.Resolved resolved(GameTestHelper h, SourceDefinition source, Block block, BlockPos relative) {
		h.setBlock(relative, block);
		return new AbsorbHandler.Resolved(AbsorbTarget.block(h.absolutePos(relative)), source, block.getName(), null);
	}

	@GameTest
	public void vanillaClientGetsTitleActionbarAndSound(GameTestHelper h) {
		SourceDefinition source = blockSource("feedback_vanilla", Blocks.AMETHYST_BLOCK, 3);
		withSources(h, List.of(source), () -> {
			Mock m = mock(h);
			ServerPlayer p = m.player();
			h.assertFalse(ServerPlayNetworking.canSend(p, AbsorbedPayload.TYPE), "mock player is a vanilla client");
			AbsorbService.Result result = new AbsorbService.Result(true, MutationRoll.Outcome.NORMAL, 2, 1, List.of());
			AbsorbFeedback.absorbed(p, Blocks.AMETHYST_BLOCK.getName(), source, result);
			List<Object> out = m.drain();
			h.assertTrue(out.stream().anyMatch(o -> o instanceof ClientboundSetTitlesAnimationPacket), "title timing; " + out);
			h.assertTrue(out.stream().anyMatch(o -> o instanceof ClientboundSetTitleTextPacket t && key(t.text()).equals(AbsorbFeedback.TITLE_SHORT)), "short title");
			h.assertTrue(out.stream().anyMatch(o -> o instanceof ClientboundSetSubtitleTextPacket s
					&& s.text().getContents().equals(Blocks.AMETHYST_BLOCK.getName().getContents())), "source name subtitle");
			h.assertTrue(out.stream().anyMatch(o -> o instanceof ClientboundSystemChatPacket c && c.overlay()
					&& key(c.content()).equals(AbsorbFeedback.ACTIONBAR)), "actionbar");
			h.assertTrue(out.stream().anyMatch(o -> o instanceof ClientboundSoundPacket), "sound");
			h.assertTrue(out.stream().noneMatch(o -> o instanceof ClientboundCustomPayloadPacket c && c.payload() instanceof AbsorbedPayload),
					"no payload for a vanilla client");
			h.assertTrue(out.stream().noneMatch(o -> o instanceof ClientboundSystemChatPacket c && !c.overlay()), "no broadcast for a normal absorption");
		});
		h.succeed();
	}

	@GameTest
	public void moddedClientGetsPayloadOnly(GameTestHelper h) {
		SourceDefinition source = blockSource("feedback_modded", Blocks.AMETHYST_BLOCK, 3);
		withSources(h, List.of(source), () -> {
			Mock m = mock(h);
			ServerPlayer p = m.player();
			AbsorbService.Result result = new AbsorbService.Result(true, MutationRoll.Outcome.MUTATE_TRAIT, 3, 2, List.of(source.id()));
			AbsorbFeedback.absorbed(p, Blocks.AMETHYST_BLOCK.getName(), source, result, true);
			List<Object> out = m.drain();
			List<AbsorbedPayload> payloads = out.stream()
					.filter(o -> o instanceof ClientboundCustomPayloadPacket c && c.payload() instanceof AbsorbedPayload)
					.map(o -> (AbsorbedPayload) ((ClientboundCustomPayloadPacket) o).payload()).toList();
			h.assertValueEqual(payloads.size(), 1, "one payload; " + out);
			AbsorbedPayload payload = payloads.getFirst();
			h.assertValueEqual(payload.outcome(), MutationRoll.Outcome.MUTATE_TRAIT, "outcome");
			h.assertValueEqual(key(payload.actionbar()), AbsorbFeedback.ACTIONBAR, "actionbar key");
			h.assertTrue(payload.notice().isPresent() && key(payload.notice().get()).equals(AbsorbFeedback.EVICTED), "eviction notice");
			h.assertTrue(out.stream().noneMatch(o -> o instanceof ClientboundSetTitleTextPacket || o instanceof ClientboundSoundPacket
					|| o instanceof ClientboundSystemChatPacket c && c.overlay()), "the client decides title / actionbar / sound itself");
			h.assertTrue(out.stream().anyMatch(o -> o instanceof ClientboundSystemChatPacket c && !c.overlay()
					&& key(c.content()).equals("absorbaholic.announce.mutate_trait")), "loud broadcast still sent");
		});
		h.succeed();
	}

	// ---- dragon, use suppression, end to end ------------------------------------------------------------------

	@GameTest
	public void dragonDiscardedAndFightNotified(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		EnderDragon dragon = EntityTypes.ENDER_DRAGON.create(level, EntitySpawnReason.TRIGGERED);
		h.assertTrue(dragon != null, "dragon created");
		Vec3 pos = h.absoluteVec(new Vec3(4, 3, 4));
		dragon.snapTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		dragon.setNoAi(true);
		level.addFreshEntity(dragon);
		EnderDragonPart part = dragon.getSubEntities()[0];
		h.assertTrue(AbsorbHandler.livingTarget(level, part.getId()) == dragon, "a dragon part resolves to its dragon");
		h.assertFalse(AbsorbService.notifyDragonFight(level, dragon), "no dragon fight in the overworld");
		AbsorbService.consumeEntity(level, dragon);
		h.assertTrue(dragon.isRemoved() && dragon.getRemovalReason() == Entity.RemovalReason.DISCARDED, "dragon discarded");
		h.assertTrue(AbsorbHandler.livingTarget(level, dragon.getId()) == null, "a removed dragon is no target");
		assertNoDropsOrXp(h);

		ServerLevel end = level.getServer().getLevel(Level.END);
		if (end != null && end.getDragonFight() != null) {
			EnderDragonFight fight = end.getDragonFight();
			boolean killedBefore = fight.hasPreviouslyKilledDragon();
			EnderDragon stranger = EntityTypes.ENDER_DRAGON.create(end, EntitySpawnReason.TRIGGERED);
			h.assertTrue(AbsorbService.notifyDragonFight(end, stranger), "the End's fight is notified");
			h.assertValueEqual(fight.hasPreviouslyKilledDragon(), killedBefore, "the fight ignores a dragon that is not its own");
		}
		h.succeed();
	}

	@GameTest
	public void useSuppressedOnlyForModdedGesture(GameTestHelper h) {
		SourceDefinition source = blockSource("use_guard", Blocks.BARREL, 3);
		withSources(h, List.of(source), () -> {
			h.setBlock(TARGET, Blocks.BARREL);
			h.setBlock(TARGET.east(), Blocks.CHEST);
			ServerPlayer p = mock(h).player();
			BlockPos barrel = h.absolutePos(TARGET);
			h.assertTrue(AbsorbHandler.suppressesUse(p, barrel, null, true), "modded gesture at an absorbable");
			h.assertFalse(AbsorbHandler.suppressesUse(p, barrel, null, false), "vanilla clients are never affected");
			h.assertFalse(AbsorbHandler.suppressesUse(p, h.absolutePos(TARGET.east()), null, true), "not absorbable");
			p.setShiftKeyDown(false);
			h.assertFalse(AbsorbHandler.suppressesUse(p, barrel, null, true), "not sneaking");
		});
		h.succeed();
	}

	/** One channel through the real receivers' tick wiring (END_SERVER_TICK), heartbeating every tick. */
	@GameTest(maxTicks = 100)
	public void channelCompletesOnServerTicks(GameTestHelper h) {
		SourceDefinition source = blockSource("e2e_anchor", Blocks.RESPAWN_ANCHOR, 3);
		Supplier<Boolean> ensureSource = () -> {
			if (SourceRegistry.byId(source.id()).isPresent()) return true;
			List<SourceDefinition> all = new ArrayList<>(SourceRegistry.all());
			all.add(source);
			SourceRegistry.set(all);
			return true;
		};
		TestSupport.setMode(h, true);
		ensureSource.get();
		h.setBlock(TARGET, Blocks.RESPAWN_ANCHOR);
		Mock m = mock(h);
		ServerPlayer p = m.player();
		lookAtBlock(h, p, TARGET);
		AbsorbTarget target = AbsorbTarget.block(h.absolutePos(TARGET));
		h.onEachTick(() -> {
			if (PlayerData.traits(p).get(source.id()).isPresent()) return;
			TestSupport.setMode(h, true);
			ensureSource.get();
			AbsorbHandler.start(p, target, now(h));
		});
		h.succeedWhen(() -> {
			h.assertBlockPresent(Blocks.AIR, TARGET);
			h.assertTrue(entry(h, p, source).traitLevel() >= 1, "absorbed");
			assertNoDropsOrXp(h);
			List<SourceDefinition> rest = new ArrayList<>(SourceRegistry.all());
			rest.removeIf(s -> s.id().equals(source.id()));
			SourceRegistry.set(rest);
		});
	}
}
