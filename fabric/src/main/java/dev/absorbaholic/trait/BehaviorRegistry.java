package dev.absorbaholic.trait;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.MapCodec;
import dev.absorbaholic.Absorbaholic;
import net.minecraft.resources.Identifier;

/**
 * All behavior types by id. Filled once during mod initialization ({@code BuiltinBehaviors.register()}), read-only
 * afterwards (so reads from the reload thread are safe).
 */
public final class BehaviorRegistry {
	private static final Map<Identifier, BehaviorType<?>> TYPES = new LinkedHashMap<>();

	private BehaviorRegistry() {}

	/** Registers a type; duplicate ids are a programming error. */
	public static <P> BehaviorType<P> register(BehaviorType<P> type) {
		if (TYPES.putIfAbsent(type.id(), type) != null) {
			throw new IllegalStateException("Duplicate behavior type " + type.id());
		}
		return type;
	}

	/** Shorthand: {@code absorbaholic:<path>}. */
	public static <P> BehaviorType<P> register(String path, MapCodec<P> codec, Behavior<P> behavior) {
		return register(BehaviorType.of(Absorbaholic.id(path), codec, behavior));
	}

	public static Optional<BehaviorType<?>> get(Identifier id) {
		return Optional.ofNullable(TYPES.get(id));
	}

	public static Collection<BehaviorType<?>> all() {
		return Collections.unmodifiableCollection(TYPES.values());
	}
}
