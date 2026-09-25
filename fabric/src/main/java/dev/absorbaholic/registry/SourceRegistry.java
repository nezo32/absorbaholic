package dev.absorbaholic.registry;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The server's current set of valid sources (static: one server per JVM; the integrated server's thread only).
 * Replaced atomically by {@link SourceLoader} on every datapack (re)load; lookups go through a {@link SourceMatcher}.
 */
public final class SourceRegistry {
	private static volatile SourceMatcher<SourceDefinition> current = SourceMatcher.empty();

	private SourceRegistry() {}

	/**
	 * Called from Absorbaholic#onInitialize. WP-REG: registers {@link SourceLoader} as a SERVER_DATA reload listener
	 * (Fabric ResourceLoader v1), clears the matcher cache on CommonLifecycleEvents.TAGS_LOADED (+ reports tag
	 * overlaps), and hooks {@link SourceSync} on ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.
	 */
	public static void register() {
		SourceLoader.register();
		SourceSync.register();
	}

	/** Replaces all sources (loader, gametests). */
	public static void set(Collection<SourceDefinition> sources) {
		current = new SourceMatcher<>(sources);
	}

	public static SourceMatcher<SourceDefinition> matcher() {
		return current;
	}

	public static List<SourceDefinition> all() {
		return current.all();
	}

	public static Optional<SourceDefinition> byId(Identifier id) {
		return current.byId(id);
	}

	public static Optional<SourceDefinition> forBlock(BlockState state) {
		return current.forBlock(state);
	}

	public static Optional<SourceDefinition> forFluid(FluidState fluid) {
		return current.forFluid(fluid);
	}

	public static Optional<SourceDefinition> forEntity(EntityType<?> type) {
		return current.forEntity(type);
	}
}
