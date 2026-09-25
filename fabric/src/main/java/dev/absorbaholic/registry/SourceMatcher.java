package dev.absorbaholic.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.SourceKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Finds the source of a block state, fluid or entity type. Shared by the server ({@link SourceDefinition}) and the
 * client ({@link SourceSummary}, for the HUD hint and the use-key intercept), so both sides agree.
 *
 * <p>Rules: {@code #absorbaholic:unabsorbable} always wins (no source); a direct id beats a tag; among several direct
 * ids or several tags the lexicographically smallest source id wins (a direct-id conflict is logged once at build
 * time, tag overlaps are reported by the registry after tags load). Fluids resolve through their legacy block
 * ({@code minecraft:lava}). Results are cached per Block / EntityType; call {@link #clearCache()} after tags change.
 * Not thread safe: each side uses its own instance on its own main thread.
 */
public final class SourceMatcher<T extends SourceLike> {
	private final Map<Identifier, T> byId;
	private final List<T> all;
	private final Map<Block, T> directBlocks = new IdentityHashMap<>();
	private final Map<EntityType<?>, T> directEntities = new IdentityHashMap<>();
	/** (tag, source) pairs in source-id order, per kind. */
	private final List<Map.Entry<TagKey<Block>, T>> blockTags = new ArrayList<>();
	private final List<Map.Entry<TagKey<EntityType<?>>, T>> entityTags = new ArrayList<>();
	private final Map<Block, Optional<T>> blockCache = new HashMap<>();
	private final Map<EntityType<?>, Optional<T>> entityCache = new HashMap<>();

	public SourceMatcher(Collection<T> sources) {
		List<T> sorted = new ArrayList<>(sources);
		sorted.sort(Comparator.comparing(s -> s.id().toString()));
		Map<Identifier, T> ids = new LinkedHashMap<>();
		for (T source : sorted) {
			ids.put(source.id(), source);
			SourceTargets t = source.targets();
			if (t.kind() == SourceKind.BLOCK) {
				for (Identifier id : t.ids()) {
					BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> putDirect(directBlocks, block, source, id));
				}
				for (Identifier tag : t.tags()) blockTags.add(Map.entry(TagKey.create(Registries.BLOCK, tag), source));
			} else {
				for (Identifier id : t.ids()) {
					BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(type -> putDirect(directEntities, type, source, id));
				}
				for (Identifier tag : t.tags()) entityTags.add(Map.entry(TagKey.create(Registries.ENTITY_TYPE, tag), source));
			}
		}
		this.byId = Map.copyOf(ids);
		this.all = List.copyOf(ids.values());
	}

	private static <K, T extends SourceLike> void putDirect(Map<K, T> map, K key, T source, Identifier target) {
		T existing = map.putIfAbsent(key, source); // sources arrive sorted: the first (smallest id) stays
		if (existing != null && existing != source) {
			Absorbaholic.LOGGER.warn("Absorbaholic sources {} and {} both target {}; {} wins", existing.id(), source.id(), target, existing.id());
		}
	}

	public static <T extends SourceLike> SourceMatcher<T> empty() {
		return new SourceMatcher<>(List.of());
	}

	public Optional<T> byId(Identifier id) {
		return Optional.ofNullable(byId.get(id));
	}

	/** All sources, sorted by id. */
	public List<T> all() {
		return all;
	}

	public int size() {
		return byId.size();
	}

	/** Source of a block state (never for {@code #absorbaholic:unabsorbable}). Air has no source unless a pack says so. */
	public Optional<T> forBlock(BlockState state) {
		if (state.is(AbsorbTags.UNABSORBABLE_BLOCKS)) return Optional.empty();
		return blockCache.computeIfAbsent(state.getBlock(), block -> {
			T direct = directBlocks.get(block);
			if (direct != null) return Optional.of(direct);
			for (Map.Entry<TagKey<Block>, T> e : blockTags) {
				if (state.is(e.getKey())) return Optional.of(e.getValue());
			}
			return Optional.empty();
		});
	}

	/** Source of a fluid, through its legacy block (lava → minecraft:lava). Empty fluid → empty. */
	public Optional<T> forFluid(FluidState fluid) {
		if (fluid.isEmpty()) return Optional.empty();
		return forBlock(fluid.createLegacyBlock());
	}

	/** Source of an entity type (never for {@code #absorbaholic:unabsorbable}). */
	public Optional<T> forEntity(EntityType<?> type) {
		if (type.builtInRegistryHolder().is(AbsorbTags.UNABSORBABLE_ENTITIES)) return Optional.empty();
		return entityCache.computeIfAbsent(type, t -> {
			T direct = directEntities.get(t);
			if (direct != null) return Optional.of(direct);
			for (Map.Entry<TagKey<EntityType<?>>, T> e : entityTags) {
				if (t.builtInRegistryHolder().is(e.getKey())) return Optional.of(e.getValue());
			}
			return Optional.empty();
		});
	}

	/** Forget cached lookups (tags were reloaded). */
	public void clearCache() {
		blockCache.clear();
		entityCache.clear();
	}
}
