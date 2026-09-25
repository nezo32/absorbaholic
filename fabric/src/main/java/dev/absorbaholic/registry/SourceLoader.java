package dev.absorbaholic.registry;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.absorbaholic.Absorbaholic;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.SourceSpecParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;

/**
 * Loads the sources from {@code data/<ns>/absorbaholic/source/<path>.json} (id {@code <ns>:<path>}; the top pack wins
 * per id, so a datapack overrides or removes a shipped source with a file at the same path).
 * <ul>
 * <li>prepare (worker thread): {@link #read} every file as strict JSON and parse it structurally (pure).</li>
 * <li>apply (server thread): {@link #load} = {@link SourceResolver} for each parsed file, then
 * {@link SourceRegistry#set}. Each bad file is skipped with one WARN naming the file and all its errors;
 * {@code {"disabled": true}} removes the id silently; one INFO line sums it up. Never throws.</li>
 * <li>{@code TAGS_LOADED} (server side, after every (re)load): clears the matcher cache and WARNs about tag overlaps,
 * unknown tags and direct targets that are {@code #absorbaholic:unabsorbable} ({@link #tagReport}).</li>
 * </ul>
 */
public final class SourceLoader {
	public static final Identifier LISTENER_ID = Absorbaholic.id("sources");
	/** {@code data/<ns>/absorbaholic/source/<path>.json} ↔ {@code <ns>:<path>}. */
	public static final FileToIdConverter LISTER = FileToIdConverter.json("absorbaholic/source");
	/** How many conflicting blocks / entity types a tag-overlap warning names before "…". */
	private static final int REPORT_EXAMPLES = 5;

	private static volatile LoadResult last = LoadResult.EMPTY;

	private SourceLoader() {}

	/** One source file as read by {@link #read}: its id, a readable file name and the structural parse result. */
	public record RawSource(Identifier id, String file, SourceSpecParser.Result parsed) {}

	/** Why one file was skipped. {@code unknownBehaviorTypes} lists behavior types it uses that aren't registered. */
	public record Skipped(String file, List<String> errors, Set<Identifier> unknownBehaviorTypes) {
		public Skipped {
			errors = List.copyOf(errors);
			unknownBehaviorTypes = Set.copyOf(unknownBehaviorTypes);
		}
	}

	/**
	 * The outcome of one load: valid sources (sorted by id), skipped files, ids removed by {@code {"disabled": true}}
	 * and non-fatal warnings per id.
	 */
	public record LoadResult(List<SourceDefinition> sources, Map<Identifier, Skipped> skipped, Set<Identifier> disabled,
			Map<Identifier, List<String>> warnings) {
		public static final LoadResult EMPTY = new LoadResult(List.of(), Map.of(), Set.of(), Map.of());

		public LoadResult {
			sources = List.copyOf(sources);
			skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skipped));
			disabled = Collections.unmodifiableSet(new LinkedHashSet<>(disabled));
			warnings = Collections.unmodifiableMap(new LinkedHashMap<>(warnings));
		}

		public Optional<SourceDefinition> source(Identifier id) {
			return sources.stream().filter(s -> s.id().equals(id)).findFirst();
		}
	}

	/** Called from {@link SourceRegistry#register()}. */
	public static void register() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(LISTENER_ID, new Listener());
		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
			if (!client) onServerTagsLoaded(registries);
		});
	}

	/** The result of the last datapack (re)load on this server (gametests, diagnostics). */
	public static LoadResult lastResult() {
		return last;
	}

	/** Reads and structurally parses every source file (top pack per id), sorted by id. Pure I/O + Gson; never throws. */
	public static List<RawSource> read(ResourceManager manager) {
		Map<Identifier, RawSource> out = new TreeMap<>(Comparator.comparing(Identifier::toString));
		for (Map.Entry<Identifier, Resource> e : LISTER.listMatchingResources(manager).entrySet()) {
			Identifier id = LISTER.fileToId(e.getKey());
			String file = "data/" + e.getKey().getNamespace() + "/" + e.getKey().getPath() + " in pack " + e.getValue().sourcePackId();
			SourceSpecParser.Result parsed;
			try (Reader reader = e.getValue().openAsReader()) {
				parsed = SourceSpecParser.read(reader);
			} catch (Exception ex) {
				parsed = new SourceSpecParser.Result.Invalid(List.of("cannot read file: " + ex));
			}
			out.put(id, new RawSource(id, file, parsed));
		}
		return List.copyOf(out.values());
	}

	/** Resolves every parsed file against the game. {@code ops} decodes behavior params. Never throws. */
	public static LoadResult load(Collection<RawSource> files, DynamicOps<JsonElement> ops) {
		List<SourceDefinition> sources = new ArrayList<>();
		Map<Identifier, Skipped> skipped = new LinkedHashMap<>();
		Set<Identifier> disabled = new LinkedHashSet<>();
		Map<Identifier, List<String>> warnings = new LinkedHashMap<>();
		List<RawSource> sorted = new ArrayList<>(files);
		sorted.sort(Comparator.comparing(f -> f.id().toString()));
		for (RawSource file : sorted) {
			switch (file.parsed()) {
				case SourceSpecParser.Result.Disabled d -> disabled.add(file.id());
				case SourceSpecParser.Result.Invalid(List<String> errors) -> skipped.put(file.id(), new Skipped(file.file(), errors, Set.of()));
				case SourceSpecParser.Result.Parsed(var spec) -> {
					List<String> errors = new ArrayList<>();
					List<String> fileWarnings = new ArrayList<>();
					SourceDefinition definition = SourceResolver.resolve(file.id(), spec, ops, errors::add, fileWarnings::add);
					if (definition != null) {
						sources.add(definition);
					} else {
						if (errors.isEmpty()) errors.add("could not be resolved");
						skipped.put(file.id(), new Skipped(file.file(), errors, SourceResolver.unknownBehaviorTypes(spec)));
					}
					if (!fileWarnings.isEmpty()) warnings.put(file.id(), List.copyOf(fileWarnings));
				}
			}
		}
		return new LoadResult(sources, skipped, disabled, warnings);
	}

	/** Logs a load, publishes its sources and remembers it. Server thread. */
	static void apply(List<RawSource> files, LoadResult result) {
		Map<Identifier, String> fileNames = new LinkedHashMap<>();
		for (RawSource f : files) fileNames.put(f.id(), f.file());
		result.warnings().forEach((id, list) ->
				Absorbaholic.LOGGER.warn("Absorbaholic source {} ({}): {}", id, fileNames.getOrDefault(id, "?"), String.join("; ", list)));
		result.skipped().forEach((id, s) ->
				Absorbaholic.LOGGER.warn("Skipping Absorbaholic source {} ({}): {}", id, s.file(), String.join("; ", s.errors())));
		result.disabled().forEach(id -> Absorbaholic.LOGGER.debug("Absorbaholic source {} is disabled by a datapack", id));
		SourceRegistry.set(result.sources());
		last = result;
		Absorbaholic.LOGGER.info("Loaded {} Absorbaholic sources ({} skipped, {} disabled)",
				result.sources().size(), result.skipped().size(), result.disabled().size());
	}

	private static void onServerTagsLoaded(HolderLookup.Provider registries) {
		try {
			SourceRegistry.matcher().clearCache();
			for (String warning : tagReport(registries, SourceRegistry.all())) Absorbaholic.LOGGER.warn(warning);
		} catch (RuntimeException e) {
			Absorbaholic.LOGGER.error("Absorbaholic: checking source tags failed", e);
		}
	}

	/**
	 * Problems only visible once tags are bound: unknown tags, direct targets in {@code #absorbaholic:unabsorbable}, and
	 * blocks / entity types matched by the tags of two or more sources and by no direct id (the smallest source id
	 * wins them; one line per group of sources).
	 */
	public static List<String> tagReport(HolderLookup.Provider registries, Collection<SourceDefinition> sources) {
		List<SourceDefinition> sorted = new ArrayList<>(sources);
		sorted.sort(Comparator.comparing(s -> s.id().toString()));
		List<String> out = new ArrayList<>();
		report(out, registries.lookupOrThrow(Registries.BLOCK), Registries.BLOCK, AbsorbTags.UNABSORBABLE_BLOCKS, SourceKind.BLOCK, "block", sorted);
		report(out, registries.lookupOrThrow(Registries.ENTITY_TYPE), Registries.ENTITY_TYPE, AbsorbTags.UNABSORBABLE_ENTITIES, SourceKind.ENTITY,
				"entity type", sorted);
		return out;
	}

	private static <T> void report(List<String> out, HolderLookup.RegistryLookup<T> lookup, ResourceKey<? extends Registry<T>> registry,
			TagKey<T> unabsorbable, SourceKind kind, String noun, List<SourceDefinition> sources) {
		Set<Identifier> direct = new LinkedHashSet<>();
		Map<Holder<T>, Set<Identifier>> byTag = new LinkedHashMap<>();
		for (SourceDefinition source : sources) {
			if (source.kind() != kind) continue;
			for (Identifier id : source.targets().ids()) {
				direct.add(id);
				lookup.get(ResourceKey.create(registry, id)).filter(h -> h.is(unabsorbable)).ifPresent(h -> out.add("Absorbaholic source "
						+ source.id() + " targets " + noun + " " + id + ", which is #" + unabsorbable.location() + " and never absorbable"));
			}
			for (Identifier tag : source.targets().tags()) {
				Optional<HolderSet.Named<T>> set = lookup.get(TagKey.create(registry, tag));
				if (set.isEmpty()) {
					out.add("Absorbaholic source " + source.id() + " targets unknown " + noun + " tag #" + tag);
					continue;
				}
				for (Holder<T> holder : set.get()) byTag.computeIfAbsent(holder, h -> new LinkedHashSet<>()).add(source.id());
			}
		}
		Map<List<Identifier>, List<String>> groups = new LinkedHashMap<>();
		byTag.forEach((holder, ids) -> {
			if (ids.size() < 2 || holder.is(unabsorbable)) return;
			Optional<Identifier> id = holder.unwrapKey().map(ResourceKey::identifier);
			if (id.isEmpty() || direct.contains(id.get())) return;
			groups.computeIfAbsent(List.copyOf(ids), k -> new ArrayList<>()).add(id.get().toString());
		});
		groups.forEach((ids, targets) -> {
			String examples = String.join(", ", targets.subList(0, Math.min(REPORT_EXAMPLES, targets.size())))
					+ (targets.size() > REPORT_EXAMPLES ? ", …" : "");
			out.add("Absorbaholic sources " + ids.stream().map(Identifier::toString).reduce((a, b) -> a + ", " + b).orElse("")
					+ " all match " + targets.size() + " " + noun + (targets.size() == 1 ? "" : "s") + " by tag (" + examples + "); "
					+ ids.getFirst() + " wins");
		});
	}

	/** The reload listener: read off-thread, resolve and publish on the server thread. */
	private static final class Listener extends SimpleReloadListener<List<RawSource>> {
		@Override
		protected List<RawSource> prepare(PreparableReloadListener.SharedState state) {
			try {
				return read(state.resourceManager());
			} catch (RuntimeException e) {
				Absorbaholic.LOGGER.error("Absorbaholic: reading sources failed; no sources loaded", e);
				return List.of();
			}
		}

		@Override
		protected void apply(List<RawSource> prepared, PreparableReloadListener.SharedState state) {
			try {
				SourceLoader.apply(prepared, load(prepared, ops(state)));
			} catch (RuntimeException e) {
				Absorbaholic.LOGGER.error("Absorbaholic: loading sources failed; keeping the previous set", e);
			}
		}

		/** RegistryOps over the reloading registries when Fabric provides them, else plain JSON. */
		private static DynamicOps<JsonElement> ops(PreparableReloadListener.SharedState state) {
			try {
				return RegistryOps.create(JsonOps.INSTANCE, state.get(ResourceLoader.REGISTRY_LOOKUP_KEY));
			} catch (RuntimeException e) {
				return JsonOps.INSTANCE;
			}
		}
	}
}
