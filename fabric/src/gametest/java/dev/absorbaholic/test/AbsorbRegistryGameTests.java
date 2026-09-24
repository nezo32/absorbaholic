package dev.absorbaholic.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.SourceSpec;
import dev.absorbaholic.core.SourceSpecParser;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.net.SourcesSyncPayload;
import dev.absorbaholic.registry.AbsorbTags;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceLoader;
import dev.absorbaholic.registry.SourceMatcher;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.registry.SourceResolver;
import dev.absorbaholic.registry.SourceSummary;
import dev.absorbaholic.registry.SourceTargets;
import dev.absorbaholic.trait.BehaviorRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

/**
 * WP-REG. Server gametests: shipped sources against the real game, lookups by BlockState / FluidState / EntityType,
 * conflict rules and the tag report with real tags, the unabsorbable tag, datapack disable, resolver errors, and the
 * sources sync on /reload (SYNC_DATA_PACK_CONTENTS). Tests that swap {@link SourceRegistry} restore it in finally
 * within the same tick.
 */
public class AbsorbRegistryGameTests {
	/**
	 * Lead's switch: true once every behavior package has landed. Then no shipped source may be skipped at all;
	 * until then a source may only be skipped because it uses a behavior type that isn't registered yet.
	 */
	private static final boolean STRICT_ALL_RESOLVE = false;
	/** At least this many are shipped with the mod (design/sources.md). */
	private static final int SHIPPED_SOURCES = 128;

	private static Identifier testId(String path) {
		return Identifier.fromNamespaceAndPath("absorbaholic_test", path);
	}

	private static SourceDefinition source(String path, SourceKind kind, List<String> ids, List<String> tags) {
		return new SourceDefinition(testId(path),
				new SourceTargets(kind, ids.stream().map(Identifier::parse).toList(), tags.stream().map(Identifier::parse).toList()),
				Optional.empty(), 0xFFFFFF, Tier.COMMON, 3,
				new SourceDefinition.Side("test_trait", List.of(), List.of()), new SourceDefinition.Side("test_weakness", List.of(), List.of()));
	}

	private static List<SourceLoader.RawSource> shippedFiles(GameTestHelper h) {
		return SourceLoader.read(h.getLevel().getServer().getResourceManager()).stream()
				.filter(r -> r.id().getNamespace().equals("absorbaholic")).toList();
	}

	/**
	 * Every shipped source either resolved in the last load, or was skipped only because it uses behavior types that are
	 * not registered yet. A source whose behavior types are all registered must resolve, with no warnings.
	 */
	@GameTest
	public void allShippedSourcesResolve(GameTestHelper h) {
		List<SourceLoader.RawSource> files = shippedFiles(h);
		SourceLoader.LoadResult last = SourceLoader.lastResult();
		h.assertTrue(files.size() >= SHIPPED_SOURCES, "shipped source files: " + files.size());
		List<String> problems = new ArrayList<>();
		int resolved = 0;
		int pending = 0;
		for (SourceLoader.RawSource file : files) {
			Identifier id = file.id();
			switch (file.parsed()) {
				case SourceSpecParser.Result.Disabled d -> problems.add(id + ": a shipped source is disabled");
				case SourceSpecParser.Result.Invalid(List<String> errors) -> problems.add(id + ": invalid " + errors);
				case SourceSpecParser.Result.Parsed(SourceSpec spec) -> {
					Set<Identifier> unknown = SourceResolver.unknownBehaviorTypes(spec);
					SourceLoader.Skipped skipped = last.skipped().get(id);
					// a target id missing from this game version (e.g. a 26.3 block on 26.2) is expected; anything else is a data bug
					List<String> warnings = last.warnings().getOrDefault(id, List.of()).stream()
							.filter(w -> !w.endsWith(" in targets, ignored")).toList();
					if (unknown.isEmpty()) {
						if (last.source(id).isEmpty()) problems.add(id + ": all behavior types exist but it was skipped: " + (skipped == null ? "?" : skipped.errors()));
						else resolved++;
						if (!warnings.isEmpty()) problems.add(id + ": warnings " + warnings);
					} else {
						pending++;
						if (STRICT_ALL_RESOLVE) problems.add(id + ": behavior types not registered " + unknown);
						if (skipped == null) {
							problems.add(id + ": uses unregistered behavior types " + unknown + " but was not skipped");
						} else {
							List<String> other = skipped.errors().stream().filter(e -> !e.contains(SourceResolver.UNKNOWN_BEHAVIOR)).toList();
							if (!other.isEmpty()) problems.add(id + ": skipped for more than missing behavior types: " + other);
							if (!skipped.unknownBehaviorTypes().equals(unknown)) problems.add(id + ": skip reason " + skipped.unknownBehaviorTypes() + " vs " + unknown);
						}
					}
				}
			}
		}
		h.assertTrue(problems.isEmpty(), Math.min(problems.size(), 20) + " of " + problems.size() + " problems (registered behavior types: "
				+ BehaviorRegistry.all().size() + "): " + String.join(" | ", problems.subList(0, Math.min(problems.size(), 20))));
		h.assertValueEqual(resolved + pending, files.size(), "resolved + pending");
		h.assertValueEqual(last.sources().stream().filter(s -> s.id().getNamespace().equals("absorbaholic")).count(), (long) resolved,
				"resolved shipped sources in the last load");
		h.succeed();
	}

	/** Every source of the last load re-resolves identically with the server's registries (RegistryOps), and its names are set. */
	@GameTest
	public void loadedSourcesAreConsistent(GameTestHelper h) {
		DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, h.getLevel().registryAccess());
		SourceLoader.LoadResult again = SourceLoader.load(SourceLoader.read(h.getLevel().getServer().getResourceManager()), ops);
		SourceLoader.LoadResult last = SourceLoader.lastResult();
		h.assertValueEqual(again.sources().stream().map(SourceDefinition::id).toList(), last.sources().stream().map(SourceDefinition::id).toList(),
				"re-resolved source ids");
		h.assertValueEqual(again.skipped().keySet(), last.skipped().keySet(), "re-resolved skipped ids");
		for (SourceDefinition d : last.sources()) {
			h.assertFalse(d.nameKey().isBlank(), d.id() + " has a display name key");
			h.assertTrue(d.maxLevel() >= 1 && d.maxLevel() <= 5, d.id() + " max level");
			if (d.targets().ids().size() + d.targets().tags().size() > 1) {
				h.assertTrue(d.nameKey().startsWith("absorbaholic.source."), d.id() + " is multi-target, so its name must be ours: " + d.nameKey());
			}
		}
		h.succeed();
	}

	/** Lookups by BlockState (direct and by tag), FluidState (source and flowing, via the legacy block) and EntityType. */
	@GameTest
	public void lookupByBlockStateFluidStateAndEntityType(GameTestHelper h) {
		List<SourceDefinition> saved = SourceRegistry.all();
		try {
			SourceRegistry.set(List.of(
					source("obsidian", SourceKind.BLOCK, List.of("minecraft:obsidian"), List.of()),
					source("wood", SourceKind.BLOCK, List.of(), List.of("minecraft:logs")),
					source("lava", SourceKind.BLOCK, List.of("minecraft:lava"), List.of()),
					source("zombie", SourceKind.ENTITY, List.of("minecraft:zombie"), List.of()),
					source("skeletons", SourceKind.ENTITY, List.of(), List.of("minecraft:skeletons"))));
			h.assertValueEqual(SourceRegistry.forBlock(Blocks.OBSIDIAN.defaultBlockState()).map(SourceDefinition::id), Optional.of(testId("obsidian")), "obsidian");
			h.assertValueEqual(SourceRegistry.forBlock(Blocks.DARK_OAK_LOG.defaultBlockState()).map(SourceDefinition::id), Optional.of(testId("wood")), "#logs");
			h.assertValueEqual(SourceRegistry.forBlock(Blocks.STRIPPED_BIRCH_WOOD.defaultBlockState()).map(SourceDefinition::id), Optional.of(testId("wood")),
					"#logs contains stripped wood");
			h.assertTrue(SourceRegistry.forBlock(Blocks.STONE.defaultBlockState()).isEmpty(), "no source for stone");
			h.assertTrue(SourceRegistry.forBlock(Blocks.OAK_PLANKS.defaultBlockState()).isEmpty(), "planks are not logs");

			BlockPos pos = new BlockPos(1, 1, 1);
			h.setBlock(pos, Blocks.LAVA);
			ServerLevel level = h.getLevel();
			h.assertValueEqual(SourceRegistry.forFluid(level.getFluidState(h.absolutePos(pos))).map(SourceDefinition::id), Optional.of(testId("lava")),
					"placed lava source");
			h.assertValueEqual(SourceRegistry.forFluid(Fluids.FLOWING_LAVA.getFlowing(4, false)).map(SourceDefinition::id), Optional.of(testId("lava")),
					"flowing lava");
			h.assertTrue(SourceRegistry.forFluid(Fluids.WATER.getSource(false)).isEmpty(), "no source for water");
			h.assertTrue(SourceRegistry.forFluid(level.getFluidState(h.absolutePos(pos.above()))).isEmpty(), "air has no fluid source");
			h.setBlock(pos, Blocks.AIR);

			h.assertValueEqual(SourceRegistry.forEntity(EntityTypes.ZOMBIE).map(SourceDefinition::id), Optional.of(testId("zombie")), "zombie");
			h.assertValueEqual(SourceRegistry.forEntity(EntityTypes.WITHER_SKELETON).map(SourceDefinition::id), Optional.of(testId("skeletons")), "#skeletons");
			h.assertTrue(SourceRegistry.forEntity(EntityTypes.HUSK).isEmpty(), "husk is not a zombie by direct id");
			h.assertValueEqual(SourceRegistry.byId(testId("wood")).map(SourceDefinition::kind), Optional.of(SourceKind.BLOCK), "byId");
			h.assertValueEqual(SourceRegistry.all().size(), 5, "all");
		} finally {
			SourceRegistry.set(saved);
		}
		h.succeed();
	}

	/** A direct id beats any tag even with a larger source id; among direct ids and among tags the smallest id wins. */
	@GameTest
	public void directBeatsTagAndSmallestIdWins(GameTestHelper h) {
		List<SourceDefinition> sources = List.of(
				source("z_oak", SourceKind.BLOCK, List.of("minecraft:oak_log"), List.of()),
				source("logs_b", SourceKind.BLOCK, List.of(), List.of("minecraft:logs")),
				source("logs_a", SourceKind.BLOCK, List.of(), List.of("minecraft:logs_that_burn")),
				source("stone_b", SourceKind.BLOCK, List.of("minecraft:stone"), List.of()),
				source("stone_a", SourceKind.BLOCK, List.of("minecraft:stone"), List.of()),
				source("zombie_2", SourceKind.ENTITY, List.of(), List.of("minecraft:zombies")),
				source("zombie_10", SourceKind.ENTITY, List.of(), List.of("minecraft:zombies")),
				source("zzz_husk", SourceKind.ENTITY, List.of("minecraft:husk"), List.of()));
		SourceMatcher<SourceDefinition> m = new SourceMatcher<>(sources.reversed());
		h.assertValueEqual(m.forBlock(Blocks.OAK_LOG.defaultBlockState()).map(SourceDefinition::id), Optional.of(testId("z_oak")), "direct beats tags");
		h.assertValueEqual(m.forBlock(Blocks.BIRCH_LOG.defaultBlockState()).map(SourceDefinition::id), Optional.of(testId("logs_a")),
				"smaller id wins among tags");
		h.assertValueEqual(m.forBlock(Blocks.CRIMSON_STEM.defaultBlockState()).map(SourceDefinition::id), Optional.of(testId("logs_b")),
				"crimson stems are logs but don't burn");
		h.assertValueEqual(m.forBlock(Blocks.STONE.defaultBlockState()).map(SourceDefinition::id), Optional.of(testId("stone_a")), "smaller id wins among direct ids");
		h.assertValueEqual(m.forEntity(EntityTypes.ZOMBIE).map(SourceDefinition::id), Optional.of(testId("zombie_10")), "lexicographic, not numeric");
		h.assertValueEqual(m.forEntity(EntityTypes.HUSK).map(SourceDefinition::id), Optional.of(testId("zzz_husk")), "direct husk beats #zombies");

		List<String> report = SourceLoader.tagReport(h.getLevel().registryAccess(), sources);
		h.assertTrue(report.stream().anyMatch(r -> r.contains(testId("logs_a") + ", " + testId("logs_b"))
				&& r.contains(testId("logs_a") + " wins")), "log overlap reported: " + report);
		h.assertTrue(report.stream().noneMatch(r -> r.contains("minecraft:oak_log")), "a directly targeted block is no tag conflict: " + report);
		h.assertTrue(report.stream().anyMatch(r -> r.contains(testId("zombie_10") + ", " + testId("zombie_2")) && r.contains("entity type")
				&& !r.contains("minecraft:husk")), "zombie overlap reported without the direct husk: " + report);
		h.succeed();
	}

	/** {@code #absorbaholic:unabsorbable} wins over direct ids and tags, for the test set and for every shipped source. */
	@GameTest
	public void unabsorbableTagWins(GameTestHelper h) {
		List<SourceDefinition> sources = List.of(
				source("barrier", SourceKind.BLOCK, List.of("minecraft:barrier"), List.of()),
				source("portals", SourceKind.BLOCK, List.of(), List.of("minecraft:portals")),
				source("stand", SourceKind.ENTITY, List.of("minecraft:armor_stand", "minecraft:giant"), List.of()),
				source("undead", SourceKind.ENTITY, List.of(), List.of("minecraft:undead")));
		SourceMatcher<SourceDefinition> m = new SourceMatcher<>(sources);
		h.assertTrue(m.forBlock(Blocks.BARRIER.defaultBlockState()).isEmpty(), "barrier is unabsorbable");
		h.assertTrue(m.forBlock(Blocks.NETHER_PORTAL.defaultBlockState()).isEmpty(), "nether portal (tag target) is unabsorbable");
		h.assertTrue(m.forEntity(EntityTypes.ARMOR_STAND).isEmpty(), "armor stand is unabsorbable");
		h.assertTrue(m.forEntity(EntityTypes.GIANT).isEmpty(), "giant is unabsorbable");
		h.assertValueEqual(m.forEntity(EntityTypes.ZOMBIE).map(SourceDefinition::id), Optional.of(testId("undead")), "zombie is undead");
		List<String> report = SourceLoader.tagReport(h.getLevel().registryAccess(), sources);
		h.assertTrue(report.stream().anyMatch(r -> r.contains("minecraft:barrier") && r.contains("never absorbable")), "barrier reported: " + report);
		h.assertTrue(report.stream().anyMatch(r -> r.contains("minecraft:giant") && r.contains("never absorbable")), "giant reported: " + report);

		// the shipped set never yields a source for an unabsorbable block or entity type
		SourceMatcher<SourceDefinition> shipped = new SourceMatcher<>(SourceLoader.lastResult().sources());
		var blocks = h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK).get(AbsorbTags.UNABSORBABLE_BLOCKS);
		h.assertTrue(blocks.isPresent() && blocks.get().size() > 10, "#absorbaholic:unabsorbable (block) is loaded");
		for (Holder<Block> block : blocks.get()) {
			h.assertTrue(shipped.forBlock(block.value().defaultBlockState()).isEmpty(), block.getRegisteredName() + " must have no source");
		}
		Optional<HolderSet.Named<EntityType<?>>> entities = h.getLevel().registryAccess().lookupOrThrow(Registries.ENTITY_TYPE)
				.get(AbsorbTags.UNABSORBABLE_ENTITIES);
		h.assertTrue(entities.isPresent() && entities.get().size() >= 4, "#absorbaholic:unabsorbable (entity_type) is loaded");
		for (Holder<EntityType<?>> type : entities.get()) {
			h.assertTrue(shipped.forEntity(type.value()).isEmpty(), type.getRegisteredName() + " must have no source");
		}
		h.succeed();
	}

	/** A {@code {"disabled": true}} override removes exactly that source; nothing else changes. */
	@GameTest
	public void disabledOverrideRemovesTheSource(GameTestHelper h) {
		// the real files plus one that certainly resolves (shipped ones may still wait for their behavior types)
		Identifier victim = testId("plain");
		List<SourceLoader.RawSource> files = new ArrayList<>(SourceLoader.read(h.getLevel().getServer().getResourceManager()));
		files.add(new SourceLoader.RawSource(victim, "data/absorbaholic_test/absorbaholic/source/plain.json", SourceSpecParser.parseText("""
				{"kind": "block", "targets": ["minecraft:obsidian"], "trait": {"key": "t"}, "weakness": {"key": "w"}}""")));
		SourceLoader.LoadResult before = SourceLoader.load(files, JsonOps.INSTANCE);
		h.assertTrue(before.source(victim).isPresent(), "the plain source resolves");
		List<SourceLoader.RawSource> overridden = files.stream()
				.map(f -> f.id().equals(victim) ? new SourceLoader.RawSource(f.id(), "datapack/" + f.file(), SourceSpecParser.parseText("{\"disabled\": true}")) : f)
				.toList();
		SourceLoader.LoadResult after = SourceLoader.load(overridden, JsonOps.INSTANCE);
		h.assertTrue(after.disabled().contains(victim), "disabled recorded");
		h.assertTrue(after.source(victim).isEmpty(), "disabled source is gone");
		h.assertFalse(after.skipped().containsKey(victim), "a disabled source is not a skipped one");
		h.assertValueEqual(after.sources().stream().map(SourceDefinition::id).filter(id -> !id.equals(victim)).toList(),
				before.sources().stream().map(SourceDefinition::id).filter(id -> !id.equals(victim)).toList(), "other sources unchanged");
		h.succeed();
	}

	/** Resolver errors are readable and complete, with the server's registries. */
	@GameTest
	public void resolverReportsReadableErrors(GameTestHelper h) {
		SourceSpecParser.Result parsed = SourceSpecParser.parseText("""
				{"kind": "entity", "targets": ["minecraft:blaze", "minecraft:no_such_mob"], "max_level": 3, "icon": "minecraft:no_such_item",
				 "trait": {"key": "t", "attributes": [{"attribute": "minecraft:no_such_attribute", "operation": "add_value", "amount": 1}],
				   "behaviors": [{"type": "absorbaholic:damage_multiplier", "damage_tag": "minecraft:is_fire", "multiplier": [0.5, 0.4]}]},
				 "weakness": {"key": "w", "behaviors": [{"type": "absorbaholic:no_such_behavior"}]}}""");
		SourceSpec spec = ((SourceSpecParser.Result.Parsed) parsed).spec();
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		SourceDefinition d = SourceResolver.resolve(testId("bad"), spec, RegistryOps.create(JsonOps.INSTANCE, h.getLevel().registryAccess()),
				errors::add, warnings::add);
		h.assertTrue(d == null, "invalid source resolves to null");
		h.assertValueEqual(errors.size(), 3, "errors " + errors);
		h.assertTrue(errors.get(0).contains("unknown attribute \"minecraft:no_such_attribute\""), "attribute error: " + errors);
		h.assertTrue(errors.get(1).contains("a level array has 2 entries, max_level is 3"), "short array error: " + errors);
		h.assertTrue(errors.get(2).contains(SourceResolver.UNKNOWN_BEHAVIOR + "absorbaholic:no_such_behavior"), "behavior error: " + errors);
		h.assertTrue(warnings.stream().anyMatch(w -> w.contains("minecraft:no_such_mob")), "unknown target warning: " + warnings);
		h.assertTrue(warnings.stream().anyMatch(w -> w.contains("minecraft:no_such_item")), "unknown icon warning: " + warnings);
		h.succeed();
	}

	/** A mock player whose outbound packets can be inspected. */
	private record Mock(ServerPlayer player, EmbeddedChannel channel) {
		List<SourcesSyncPayload> drainSyncs() {
			channel.runPendingTasks();
			List<SourcesSyncPayload> out = new ArrayList<>();
			for (Object msg : channel.outboundMessages()) {
				if (msg instanceof ClientboundCustomPayloadPacket p && p.payload() instanceof SourcesSyncPayload s) out.add(s);
			}
			channel.outboundMessages().clear();
			return out;
		}
	}

	/** A connected mock player; {@code modded} = its client declared our sources channel (as a client with the mod does). */
	private static Mock mock(GameTestHelper h, boolean modded) {
		ServerLevel level = h.getLevel();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), modded ? "reg-modded" : "reg-vanilla"), false);
		ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		if (modded) {
			player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
					new RegistrationPayload(RegistrationPayload.REGISTER, List.of(SourcesSyncPayload.TYPE.id()))));
		}
		Mock mock = new Mock(player, channel);
		mock.drainSyncs();
		return mock;
	}

	/**
	 * /reload fires SYNC_DATA_PACK_CONTENTS(player, false) for every player after the reload listeners applied: the
	 * modded client gets the new source list, the vanilla client nothing.
	 */
	@GameTest
	public void reloadResendsSources(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		Mock modded = mock(h, true);
		Mock vanilla = mock(h, false);
		h.assertTrue(ServerPlayNetworking.canSend(modded.player(), SourcesSyncPayload.TYPE), "modded mock can receive sources");
		h.assertFalse(ServerPlayNetworking.canSend(vanilla.player(), SourcesSyncPayload.TYPE), "vanilla mock can't");
		List<SourceDefinition> saved = SourceRegistry.all();
		try {
			ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.invoker().onSyncDataPackContents(modded.player(), false);
			ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.invoker().onSyncDataPackContents(vanilla.player(), false);
			List<SourcesSyncPayload> first = modded.drainSyncs();
			h.assertValueEqual(first.size(), 1, "one sync payload");
			h.assertValueEqual(first.getFirst().sources(), saved.stream().map(SourceSummary::of).toList(), "current sources sent");
			h.assertValueEqual(vanilla.drainSyncs().size(), 0, "nothing for a vanilla client");

			// a reload applies a different set; the next sync carries it
			SourceRegistry.set(List.of(source("reloaded", SourceKind.BLOCK, List.of("minecraft:obsidian"), List.of())));
			ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.invoker().onSyncDataPackContents(modded.player(), false);
			List<SourcesSyncPayload> second = modded.drainSyncs();
			h.assertValueEqual(second.size(), 1, "one sync payload after reload");
			h.assertValueEqual(second.getFirst().sources().stream().map(SourceSummary::id).toList(), List.of(testId("reloaded")), "reloaded set sent");
			h.assertValueEqual(second.getFirst().sources().getFirst().nameKey(), "block.minecraft.obsidian", "display name synced");
		} finally {
			SourceRegistry.set(saved);
			server.getPlayerList().remove(modded.player());
			server.getPlayerList().remove(vanilla.player());
		}
		h.succeed();
	}

	/** The full shipped set encodes and decodes losslessly through the real payload codec. */
	@GameTest
	public void sourcesPayloadRoundTrip(GameTestHelper h) {
		SourcesSyncPayload payload = new SourcesSyncPayload(SourceLoader.lastResult().sources().stream().map(SourceSummary::of).toList());
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
		try {
			SourcesSyncPayload.CODEC.encode(buf, payload);
			SourcesSyncPayload decoded = SourcesSyncPayload.CODEC.decode(buf);
			h.assertValueEqual(decoded, payload, "decoded payload");
			h.assertValueEqual(buf.readableBytes(), 0, "bytes left unread");
		} finally {
			buf.release();
		}
		h.assertValueEqual(SourcesSyncPayload.TYPE.id().toString(), "absorbaholic:sources", "channel id");
		h.succeed();
	}

	/** Shipped multi-target sources carry their own display name; single-target ones default to the vanilla name. */
	@GameTest
	public void shippedDisplayNames(GameTestHelper h) {
		Map<Identifier, SourceDefinition> byId = new HashMap<>();
		SourceLoader.lastResult().sources().forEach(s -> byId.put(s.id(), s));
		for (SourceLoader.RawSource file : shippedFiles(h)) {
			if (!(file.parsed() instanceof SourceSpecParser.Result.Parsed(SourceSpec spec))) continue;
			SourceDefinition d = byId.get(file.id());
			if (d == null) continue; // pending behavior types (checked by allShippedSourcesResolve)
			if (spec.name().isPresent()) {
				h.assertValueEqual(d.nameKey(), spec.name().get(), file.id() + " explicit name");
			} else {
				h.assertValueEqual(d.nameKey(), SourceDefinition.defaultNameKey(d.id(), d.targets()), file.id() + " default name");
			}
		}
		h.succeed();
	}
}
