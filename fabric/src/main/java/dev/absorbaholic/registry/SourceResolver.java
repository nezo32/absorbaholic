package dev.absorbaholic.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.absorbaholic.core.SourceSpec;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * WP-REG. Turns a structurally valid {@link SourceSpec} into a {@link SourceDefinition}: target strings → ids / tag ids
 * (unknown direct ids are dropped with an error, no targets left = invalid), attribute ids → BuiltInRegistries.ATTRIBUTE
 * holders, operation names → AttributeModifier.Operation, behavior type ids → {@code BehaviorRegistry}, params →
 * {@code type.codec().codec().parse(JsonOps.INSTANCE, params)} inside {@code LevelValue.collect} so every decoded
 * LevelValue array is checked to be at least max_level long; icon → item id (must exist).
 */
public final class SourceResolver {
	private SourceResolver() {}

	/** The definition, or null with every problem passed to {@code errors}. */
	public static @Nullable SourceDefinition resolve(Identifier id, SourceSpec spec, Consumer<String> errors) {
		// TODO(WP-REG)
		errors.accept("resolver not implemented");
		return null;
	}

	/** Convenience for tests: resolve or collect errors. */
	public static List<String> errorsOf(Identifier id, SourceSpec spec) {
		List<String> errors = new ArrayList<>();
		resolve(id, spec, errors::add);
		return errors;
	}
}
