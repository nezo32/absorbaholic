package dev.absorbaholic.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.JsonOps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.SourceSpec;
import dev.absorbaholic.core.SourceSpecParser;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.net.SourcesSyncPayload;
import dev.absorbaholic.trait.behavior.DamageMultiplierBehavior;
import dev.absorbaholic.trait.behavior.WipeOnDeathBehavior;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Registry loading and validation against a bootstrapped (vanilla, mixin-free) game: resolver errors and warnings,
 * the loader over a real two-pack resource manager (override, disable, malformed files), display names, the matcher
 * rules (unabsorbable tag wins, direct beats tag, smallest id wins) with tags bound by hand, and the sync codec.
 */
class SourceLoadingTest {
	static final TagKey<Block> LOGS = TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("logs"));
	static final TagKey<Block> FIRE = TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("fire"));
	static final TagKey<EntityType<?>> SKELETONS = TagKey.create(Registries.ENTITY_TYPE, Identifier.withDefaultNamespace("skeletons"));

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// the two reference behavior types register when their classes initialize
		assertNotNull(DamageMultiplierBehavior.TYPE);
		assertNotNull(WipeOnDeathBehavior.TYPE);
		bindTags();
	}

	/** Binds a small, known tag set (vanilla binds tags only when a datapack loads). */
	private static void bindTags() {
		BuiltInRegistries.BLOCK.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, Map.of(
				AbsorbTags.UNABSORBABLE_BLOCKS, List.of(holder(Blocks.BARRIER), holder(Blocks.FIRE)),
				LOGS, List.of(holder(Blocks.OAK_LOG), holder(Blocks.BIRCH_LOG), holder(Blocks.SPRUCE_LOG)),
				FIRE, List.of(holder(Blocks.FIRE), holder(Blocks.SOUL_FIRE))))).apply();
		BuiltInRegistries.ENTITY_TYPE.prepareTagReload(new TagLoader.LoadResult<>(Registries.ENTITY_TYPE, Map.of(
				AbsorbTags.UNABSORBABLE_ENTITIES, List.<Holder<EntityType<?>>>of(EntityTypes.ARMOR_STAND.builtInRegistryHolder()),
				SKELETONS, List.<Holder<EntityType<?>>>of(EntityTypes.SKELETON.builtInRegistryHolder(), EntityTypes.STRAY.builtInRegistryHolder()))))
				.apply();
	}

	private static Holder<Block> holder(Block block) {
		return block.builtInRegistryHolder();
	}

	static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("absorbaholic_test", path);
	}

	static SourceSpec spec(String json) {
		SourceSpecParser.Result r = SourceSpecParser.parseText(json);
		if (r instanceof SourceSpecParser.Result.Parsed(SourceSpec spec)) return spec;
		throw new AssertionError("not parsed: " + r);
	}

	/** A minimal valid source JSON of {@code kind} with the given targets, trait and weakness bodies. */
	static String json(String kind, String targets, String trait, String weakness) {
		return "{\"kind\": \"" + kind + "\", \"targets\": " + targets + ", \"trait\": {\"key\": \"t\"" + trait + "}, \"weakness\": {\"key\": \"w\""
				+ weakness + "}}";
	}

	static boolean anyContains(List<String> list, String part) {
		return list.stream().anyMatch(e -> e.contains(part));
	}

	// --- resolver ---

	@Test
	void resolvesAttributesBehaviorsAndTargets() {
		SourceDefinition d = SourceResolver.resolve(id("obsidian"), spec("""
				{"kind": "block", "targets": ["minecraft:obsidian", "#minecraft:logs"], "icon": "minecraft:obsidian", "color": "#3B2754",
				 "tier": "rare", "max_level": 3,
				 "trait": {"key": "blast_resistance",
				   "attributes": [{"attribute": "minecraft:armor", "operation": "add_value", "amount": [1, 2, 3]}],
				   "behaviors": [{"type": "absorbaholic:damage_multiplier", "damage_tag": "minecraft:is_explosion", "multiplier": [0.75, 0.5, 0.3]}]},
				 "weakness": {"key": "heavy",
				   "attributes": [{"attribute": "movement_speed", "operation": "add_multiplied_total", "amount": -0.1}],
				   "behaviors": [{"type": "absorbaholic:wipe_on_death"}]}}"""), e -> {
			throw new AssertionError(e);
		});
		assertNotNull(d);
		assertEquals(new SourceTargets(SourceKind.BLOCK, List.of(Identifier.withDefaultNamespace("obsidian")), List.of(LOGS.location())), d.targets());
		assertEquals(Optional.of(Identifier.withDefaultNamespace("obsidian")), d.icon());
		assertEquals(0x3B2754, d.color());
		assertEquals(Tier.RARE, d.tier());
		assertEquals(Attributes.ARMOR, d.trait().attributes().getFirst().attribute());
		assertEquals(AttributeModifier.Operation.ADD_VALUE, d.trait().attributes().getFirst().operation());
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, d.weakness().attributes().getFirst().operation());
		assertEquals(DamageMultiplierBehavior.TYPE, d.trait().behaviors().getFirst().type());
		DamageMultiplierBehavior.Params p = (DamageMultiplierBehavior.Params) d.trait().behaviors().getFirst().params();
		assertEquals(LevelValue.perLevel(0.75, 0.5, 0.3), p.multiplier());
		assertEquals(WipeOnDeathBehavior.TYPE, d.weakness().behaviors().getFirst().type());
		assertEquals("absorbaholic.trait.blast_resistance", d.traitLangKey());
	}

	@Test
	void unknownAttributeIsAnError() {
		List<String> errors = SourceResolver.errorsOf(id("a"), spec(json("block", "[\"stone\"]",
				", \"attributes\": [{\"attribute\": \"minecraft:not_an_attribute\", \"operation\": \"add_value\", \"amount\": 1}]", "")));
		assertEquals(1, errors.size(), errors.toString());
		assertTrue(errors.getFirst().contains("trait.attributes[0]: unknown attribute \"minecraft:not_an_attribute\""), errors.toString());
	}

	@Test
	void attributeThePlayerLacksIsAnError() {
		List<String> errors = SourceResolver.errorsOf(id("a"), spec(json("block", "[\"stone\"]", "",
				", \"attributes\": [{\"attribute\": \"minecraft:follow_range\", \"operation\": \"add_value\", \"amount\": 1}]")));
		assertTrue(anyContains(errors, "weakness.attributes[0]: players have no attribute"), errors.toString());
	}

	@Test
	void unknownBehaviorTypeIsAnError() {
		SourceSpec s = spec(json("block", "[\"stone\"]", ", \"behaviors\": [{\"type\": \"absorbaholic:no_such_behavior\", \"x\": 1}]", ""));
		List<String> errors = SourceResolver.errorsOf(id("a"), s);
		assertEquals(List.of("trait.behaviors[0]: " + SourceResolver.UNKNOWN_BEHAVIOR + "absorbaholic:no_such_behavior"), errors);
		assertEquals(Set.of(Identifier.fromNamespaceAndPath("absorbaholic", "no_such_behavior")), SourceResolver.unknownBehaviorTypes(s));
	}

	@Test
	void shortBehaviorArrayIsAnError() {
		String behavior = ", \"behaviors\": [{\"type\": \"absorbaholic:damage_multiplier\", \"damage_tag\": \"minecraft:is_fire\", \"multiplier\": %s}]";
		String template = "{\"kind\": \"block\", \"targets\": [\"stone\"], \"max_level\": 4, \"trait\": {\"key\": \"t\"" + behavior + "}, \"weakness\": {\"key\": \"w\"}}";
		List<String> errors = SourceResolver.errorsOf(id("a"), spec(template.formatted("[0.9, 0.8, 0.7]")));
		assertEquals(1, errors.size(), errors.toString());
		assertTrue(errors.getFirst().contains("a level array has 3 entries, max_level is 4"), errors.toString());
		assertTrue(errors.getFirst().startsWith("trait.behaviors[0] (absorbaholic:damage_multiplier)"), errors.toString());
		assertEquals(List.of(), SourceResolver.errorsOf(id("a"), spec(template.formatted("[0.9, 0.8, 0.7, 0.6]"))));
		assertEquals(List.of(), SourceResolver.errorsOf(id("a"), spec(template.formatted("0.2"))));
	}

	@Test
	void badParamsAreReadableErrors() {
		List<String> missing = SourceResolver.errorsOf(id("a"), spec(json("block", "[\"stone\"]",
				", \"behaviors\": [{\"type\": \"absorbaholic:damage_multiplier\", \"multipler\": 0.5}]", "")));
		assertEquals(1, missing.size(), missing.toString());
		assertTrue(missing.getFirst().contains("multiplier"), missing.toString());
		List<String> typo = SourceResolver.warningsOf(id("a"), spec(json("block", "[\"stone\"]",
				", \"behaviors\": [{\"type\": \"absorbaholic:damage_multiplier\", \"multiplier\": 0.5, \"multipler\": 0.5}]", "")));
		assertEquals(List.of("trait.behaviors[0] (absorbaholic:damage_multiplier): unknown param \"multipler\" ignored"), typo);
		List<String> immunity = SourceResolver.errorsOf(id("a"), spec(json("block", "[\"stone\"]",
				", \"behaviors\": [{\"type\": \"absorbaholic:damage_multiplier\", \"multiplier\": 0}]", "")));
		assertTrue(anyContains(immunity, "immunity"), immunity.toString());
		List<String> wrongType = SourceResolver.errorsOf(id("a"), spec(json("block", "[\"stone\"]",
				", \"behaviors\": [{\"type\": \"absorbaholic:damage_multiplier\", \"multiplier\": \"lots\"}]", "")));
		assertEquals(1, wrongType.size(), wrongType.toString());
	}

	@Test
	void unknownTargetsAreDroppedButNoneLeftIsAnError() {
		SourceSpec partly = spec(json("block", "[\"stone\", \"minecraft:not_a_block\"]", "", ""));
		assertEquals(List.of(), SourceResolver.errorsOf(id("a"), partly));
		assertEquals(List.of("unknown block \"minecraft:not_a_block\" in targets, ignored"), SourceResolver.warningsOf(id("a"), partly));
		SourceDefinition d = SourceResolver.resolve(id("a"), partly, e -> {});
		assertNotNull(d);
		assertEquals(List.of(Identifier.withDefaultNamespace("stone")), d.targets().ids());

		SourceSpec none = spec(json("entity", "[\"minecraft:not_a_mob\"]", "", ""));
		assertTrue(anyContains(SourceResolver.errorsOf(id("a"), none), "no valid targets left"));
		// tags are never checked at load time
		assertEquals(List.of(), SourceResolver.errorsOf(id("a"), spec(json("entity", "[\"#absorbaholic_test:unknown_tag\"]", "", ""))));
		// an entity that is not living can never be absorbed
		assertEquals(List.of("entity type \"minecraft:item\" is not a living entity and can never be absorbed"),
				SourceResolver.warningsOf(id("a"), spec(json("entity", "[\"item\", \"zombie\"]", "", ""))));
	}

	@Test
	void unknownIconFallsBack() {
		SourceSpec s = spec("{\"kind\": \"block\", \"targets\": [\"stone\"], \"icon\": \"minecraft:no_item\", \"trait\": {\"key\": \"t\"}, \"weakness\": {\"key\": \"w\"}}");
		assertEquals(List.of(), SourceResolver.errorsOf(id("a"), s));
		assertTrue(anyContains(SourceResolver.warningsOf(id("a"), s), "unknown icon item"));
		assertEquals(Optional.empty(), SourceResolver.resolve(id("a"), s, e -> {}).icon());
	}

	@Test
	void allErrorsOfAFileAreReported() {
		List<String> errors = SourceResolver.errorsOf(id("a"), spec(json("block", "[\"stone\"]",
				", \"attributes\": [{\"attribute\": \"nope\", \"operation\": \"add_value\", \"amount\": 1}], \"behaviors\": [{\"type\": \"absorbaholic:nope\"}]",
				", \"behaviors\": [{\"type\": \"absorbaholic:damage_multiplier\"}]")));
		assertEquals(3, errors.size(), errors.toString());
	}

	// --- display name ---

	@Test
	void displayNameDefaultsAndOverride() {
		assertEquals("block.minecraft.obsidian", resolveOrFail("a", json("block", "[\"obsidian\"]", "", "")).nameKey());
		assertEquals("entity.minecraft.blaze", resolveOrFail("a", json("entity", "[\"blaze\"]", "", "")).nameKey());
		assertEquals("absorbaholic.source.multi", resolveOrFail("multi", json("block", "[\"stone\", \"granite\"]", "", "")).nameKey());
		assertEquals("absorbaholic.source.wood", resolveOrFail("wood", json("block", "[\"#minecraft:logs\"]", "", "")).nameKey());
		assertEquals("absorbaholic.source.one_and_tag", resolveOrFail("one_and_tag", json("block", "[\"stone\", \"#minecraft:logs\"]", "", "")).nameKey());
		assertEquals("absorbaholic.source.sub.dir", resolveOrFail("sub/dir", json("block", "[\"stone\", \"granite\"]", "", "")).nameKey());
		// an unknown id dropped from two targets leaves a single direct target
		assertEquals("block.minecraft.stone", resolveOrFail("a", json("block", "[\"stone\", \"not_a_block\"]", "", "")).nameKey());
		assertEquals("absorbaholic.source.wood", resolveOrFail("x",
				"{\"kind\": \"block\", \"name\": \"absorbaholic.source.wood\", \"targets\": [\"oak_log\"], \"trait\": {\"key\": \"t\"}, \"weakness\": {\"key\": \"w\"}}").nameKey());
		// the constructors without a name use the same default
		SourceDefinition noName = new SourceDefinition(id("x"), new SourceTargets(SourceKind.ENTITY, List.of(Identifier.withDefaultNamespace("zombie")), List.of()),
				Optional.empty(), 0, Tier.COMMON, 3, new SourceDefinition.Side("t", List.of(), List.of()), new SourceDefinition.Side("w", List.of(), List.of()));
		assertEquals("entity.minecraft.zombie", noName.nameKey());
		assertEquals("entity.minecraft.zombie", SourceSummary.of(noName).nameKey());
	}

	private static SourceDefinition resolveOrFail(String path, String json) {
		SourceDefinition d = SourceResolver.resolve(id(path), spec(json), e -> {
			throw new AssertionError(e);
		});
		assertNotNull(d);
		return d;
	}

	// --- loader over real packs ---

	private static void write(Path pack, String ns, String path, String content) throws IOException {
		Path file = pack.resolve("data").resolve(ns).resolve("absorbaholic/source").resolve(path + ".json");
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private static PackResources pack(String name, Path root) {
		return new PathPackResources(new PackLocationInfo(name, Component.literal(name), PackSource.BUILT_IN, Optional.empty()), root);
	}

	@Test
	void loaderReadsPacksWithOverridesDisablesAndBadFiles(@TempDir Path tmp) throws IOException {
		Path mod = tmp.resolve("mod");
		Path datapack = tmp.resolve("datapack");
		write(mod, "absorbaholic", "stone", json("block", "[\"stone\"]", "", ""));
		write(mod, "absorbaholic", "zombie", json("entity", "[\"zombie\"]", "", ""));
		write(mod, "absorbaholic", "removed", json("block", "[\"dirt\"]", "", ""));
		write(mod, "absorbaholic", "broken", "{\"kind\": \"block\",, }");
		write(mod, "absorbaholic", "future", json("block", "[\"stone\"]", ", \"behaviors\": [{\"type\": \"absorbaholic:not_yet\"}]", ""));
		write(mod, "absorbaholic", "nested/deep", json("block", "[\"granite\"]", "", ""));
		// the datapack replaces zombie, disables removed, adds its own source
		write(datapack, "absorbaholic", "zombie", json("entity", "[\"husk\"]", "", ""));
		write(datapack, "absorbaholic", "removed", "{\"disabled\": true}");
		write(datapack, "mypack", "custom", json("block", "[\"diorite\"]", "", ""));

		List<SourceLoader.RawSource> raw;
		try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("mod", mod), pack("datapack", datapack)))) {
			raw = SourceLoader.read(manager);
		}
		assertEquals(List.of("absorbaholic:broken", "absorbaholic:future", "absorbaholic:nested/deep", "absorbaholic:removed",
				"absorbaholic:stone", "absorbaholic:zombie", "mypack:custom"), raw.stream().map(r -> r.id().toString()).toList());
		SourceLoader.RawSource zombieFile = raw.stream().filter(r -> r.id().getPath().equals("zombie")).findFirst().orElseThrow();
		assertTrue(zombieFile.file().contains("data/absorbaholic/absorbaholic/source/zombie.json") && zombieFile.file().contains("datapack"),
				zombieFile.file());

		SourceLoader.LoadResult result = SourceLoader.load(raw, JsonOps.INSTANCE);
		assertEquals(List.of("absorbaholic:nested/deep", "absorbaholic:stone", "absorbaholic:zombie", "mypack:custom"),
				result.sources().stream().map(s -> s.id().toString()).toList());
		assertEquals(Set.of(Identifier.fromNamespaceAndPath("absorbaholic", "removed")), result.disabled());
		assertEquals(Set.of("absorbaholic:broken", "absorbaholic:future"),
				Set.copyOf(result.skipped().keySet().stream().map(Identifier::toString).toList()));
		SourceLoader.Skipped broken = result.skipped().get(Identifier.fromNamespaceAndPath("absorbaholic", "broken"));
		assertTrue(broken.errors().getFirst().startsWith("malformed JSON"), broken.toString());
		assertTrue(broken.unknownBehaviorTypes().isEmpty());
		SourceLoader.Skipped future = result.skipped().get(Identifier.fromNamespaceAndPath("absorbaholic", "future"));
		assertEquals(Set.of(Identifier.fromNamespaceAndPath("absorbaholic", "not_yet")), future.unknownBehaviorTypes());
		assertTrue(future.file().contains("future.json"), future.file());
		// the datapack's zombie replaced the mod's completely
		assertEquals(List.of(Identifier.withDefaultNamespace("husk")),
				result.source(Identifier.fromNamespaceAndPath("absorbaholic", "zombie")).orElseThrow().targets().ids());
	}

	@Test
	void loaderNeverThrowsOnGarbage() {
		List<SourceLoader.RawSource> raw = new ArrayList<>();
		for (String bad : List.of("", "[]", "{\"kind\": 5}", "{\"disabled\": 1}", "{", "{\"kind\": \"block\", \"targets\": [null]}")) {
			raw.add(new SourceLoader.RawSource(id("f" + raw.size()), "f" + raw.size() + ".json", SourceSpecParser.parseText(bad)));
		}
		SourceLoader.LoadResult result = SourceLoader.load(raw, JsonOps.INSTANCE);
		assertEquals(0, result.sources().size());
		assertEquals(raw.size(), result.skipped().size());
		result.skipped().values().forEach(s -> assertFalse(s.errors().isEmpty(), s.toString()));
	}

	// --- matcher rules ---

	private static SourceDefinition source(String path, SourceKind kind, List<String> ids, List<String> tags) {
		return new SourceDefinition(id(path), new SourceTargets(kind, ids.stream().map(Identifier::parse).toList(), tags.stream().map(Identifier::parse).toList()),
				Optional.empty(), 0xFFFFFF, Tier.COMMON, 3, new SourceDefinition.Side("t", List.of(), List.of()), new SourceDefinition.Side("w", List.of(), List.of()));
	}

	@Test
	void directIdBeatsTagEvenWithALargerId() {
		SourceMatcher<SourceDefinition> m = new SourceMatcher<>(List.of(
				source("a_logs", SourceKind.BLOCK, List.of(), List.of("minecraft:logs")),
				source("z_oak", SourceKind.BLOCK, List.of("minecraft:oak_log"), List.of())));
		assertEquals(id("z_oak"), m.forBlock(Blocks.OAK_LOG.defaultBlockState()).orElseThrow().id());
		assertEquals(id("a_logs"), m.forBlock(Blocks.BIRCH_LOG.defaultBlockState()).orElseThrow().id());
		assertTrue(m.forBlock(Blocks.STONE.defaultBlockState()).isEmpty());
	}

	@Test
	void smallestIdWinsAmongDirectIdsAndAmongTags() {
		List<SourceDefinition> sources = List.of(
				source("b_stone", SourceKind.BLOCK, List.of("minecraft:stone"), List.of()),
				source("a_stone", SourceKind.BLOCK, List.of("minecraft:stone"), List.of()),
				source("logs_2", SourceKind.BLOCK, List.of(), List.of("minecraft:logs")),
				source("logs_10", SourceKind.BLOCK, List.of(), List.of("minecraft:logs")),
				source("skel_b", SourceKind.ENTITY, List.of(), List.of("minecraft:skeletons")),
				source("skel_a", SourceKind.ENTITY, List.of(), List.of("minecraft:skeletons")));
		SourceMatcher<SourceDefinition> m = new SourceMatcher<>(sources);
		assertEquals(id("a_stone"), m.forBlock(Blocks.STONE.defaultBlockState()).orElseThrow().id());
		assertEquals(id("logs_10"), m.forBlock(Blocks.SPRUCE_LOG.defaultBlockState()).orElseThrow().id(), "lexicographic, not numeric");
		assertEquals(id("skel_a"), m.forEntity(EntityTypes.STRAY).orElseThrow().id());
		// the order of the input doesn't matter
		SourceMatcher<SourceDefinition> reversed = new SourceMatcher<>(sources.reversed());
		assertEquals(id("a_stone"), reversed.forBlock(Blocks.STONE.defaultBlockState()).orElseThrow().id());
		assertEquals(id("skel_a"), reversed.forEntity(EntityTypes.SKELETON).orElseThrow().id());
	}

	@Test
	void unabsorbableTagWinsOverDirectIdsAndTags() {
		SourceMatcher<SourceDefinition> m = new SourceMatcher<>(List.of(
				source("barrier", SourceKind.BLOCK, List.of("minecraft:barrier"), List.of()),
				source("fire", SourceKind.BLOCK, List.of(), List.of("minecraft:fire")),
				source("stand", SourceKind.ENTITY, List.of("minecraft:armor_stand"), List.of()),
				source("zombie", SourceKind.ENTITY, List.of("minecraft:zombie"), List.of())));
		assertTrue(m.forBlock(Blocks.BARRIER.defaultBlockState()).isEmpty());
		assertTrue(m.forBlock(Blocks.FIRE.defaultBlockState()).isEmpty(), "fire is in #fire but also unabsorbable");
		assertEquals(id("fire"), m.forBlock(Blocks.SOUL_FIRE.defaultBlockState()).orElseThrow().id());
		assertTrue(m.forEntity(EntityTypes.ARMOR_STAND).isEmpty());
		assertEquals(id("zombie"), m.forEntity(EntityTypes.ZOMBIE).orElseThrow().id());
	}

	@Test
	void fluidsResolveThroughTheirBlock() {
		SourceMatcher<SourceDefinition> m = new SourceMatcher<>(List.of(source("lava", SourceKind.BLOCK, List.of("minecraft:lava"), List.of())));
		assertEquals(id("lava"), m.forFluid(Fluids.LAVA.getSource(false)).orElseThrow().id());
		assertEquals(id("lava"), m.forFluid(Fluids.FLOWING_LAVA.getFlowing(3, false)).orElseThrow().id());
		assertTrue(m.forFluid(Fluids.WATER.getSource(false)).isEmpty());
		assertTrue(m.forFluid(Fluids.EMPTY.defaultFluidState()).isEmpty());
	}

	// --- sync codec ---

	@Test
	void summaryCodecRoundTrip() {
		SourceDefinition d = resolveOrFail("wood", """
				{"kind": "block", "name": "absorbaholic.source.wood", "targets": ["oak_log", "#minecraft:logs"], "icon": "oak_log", "color": "#8A6A3B",
				 "tier": "uncommon", "max_level": 5, "trait": {"key": "t"}, "weakness": {"key": "w"}}""");
		SourcesSyncPayload payload = new SourcesSyncPayload(List.of(SourceSummary.of(d),
				SourceSummary.of(resolveOrFail("blaze", json("entity", "[\"blaze\"]", "", "")))));
		ByteBuf buf = Unpooled.buffer();
		try {
			for (SourceSummary s : payload.sources()) SourceSummary.STREAM_CODEC.encode(buf, s);
			List<SourceSummary> decoded = new ArrayList<>();
			for (int i = 0; i < payload.sources().size(); i++) decoded.add(SourceSummary.STREAM_CODEC.decode(buf));
			assertEquals(payload.sources(), decoded);
			assertEquals(0, buf.readableBytes());
		} finally {
			buf.release();
		}
		SourceSummary wood = payload.sources().getFirst();
		assertEquals("absorbaholic.source.wood", wood.nameKey());
		assertEquals(Tier.UNCOMMON, wood.tier());
		assertEquals(5, wood.maxLevel());
		assertEquals("absorbaholic.weakness.w", wood.weaknessLangKey());
		assertEquals("entity.minecraft.blaze", payload.sources().get(1).nameKey());
	}

	@Test
	void resolverResultsAreNullOnError() {
		assertNull(SourceResolver.resolve(id("a"), spec(json("block", "[\"not_a_block\"]", "", "")), e -> {}));
	}
}
