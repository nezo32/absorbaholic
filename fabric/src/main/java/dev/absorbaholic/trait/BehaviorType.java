package dev.absorbaholic.trait;

import java.util.EnumSet;
import java.util.Set;

import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;

/**
 * A registered behavior type: its id (the {@code "type"} in source JSON), the codec of its params (use
 * {@code LevelValue.CODEC} for level-scaled numbers) and the stateless implementation. Create with {@link #of} and
 * register in a group registrar under {@code trait.behavior}.
 */
public record BehaviorType<P>(Identifier id, MapCodec<P> codec, Behavior<P> behavior, Set<Hook> hooks) {
	public BehaviorType {
		hooks = Set.copyOf(hooks);
	}

	/** Detects the overridden hooks of {@code behavior}. */
	public static <P> BehaviorType<P> of(Identifier id, MapCodec<P> codec, Behavior<P> behavior) {
		EnumSet<Hook> hooks = EnumSet.noneOf(Hook.class);
		for (Hook h : Hook.values()) {
			if (h.isOverriddenBy(behavior)) hooks.add(h);
		}
		return new BehaviorType<>(id, codec, behavior, hooks);
	}

	public boolean has(Hook hook) {
		return hooks.contains(hook);
	}
}
