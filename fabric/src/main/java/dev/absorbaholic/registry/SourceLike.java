package dev.absorbaholic.registry;

import net.minecraft.resources.Identifier;

/** Anything {@link SourceMatcher} can index: the server's {@link SourceDefinition} and the client's {@link SourceSummary}. */
public interface SourceLike {
	Identifier id();

	SourceTargets targets();
}
