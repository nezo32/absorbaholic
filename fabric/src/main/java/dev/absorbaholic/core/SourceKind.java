package dev.absorbaholic.core;

import java.util.Optional;

/** What a source's targets are: blocks (fluids via their block, e.g. minecraft:lava) or entity types. */
public enum SourceKind {
	BLOCK("block"),
	ENTITY("entity");

	private final String serializedName;

	SourceKind(String serializedName) {
		this.serializedName = serializedName;
	}

	public String serializedName() {
		return serializedName;
	}

	public static Optional<SourceKind> byName(String name) {
		for (SourceKind k : values()) {
			if (k.serializedName.equals(name)) return Optional.of(k);
		}
		return Optional.empty();
	}
}
