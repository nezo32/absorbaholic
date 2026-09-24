package dev.absorbaholic.core;

import java.util.Locale;
import java.util.Optional;

/** Rarity of a source (display and balance only). Serialized lowercase; lang key {@code absorbaholic.tier.<name>}. */
public enum Tier {
	COMMON(0xFFFFFF),
	UNCOMMON(0x55FF55),
	RARE(0x55FFFF),
	EPIC(0xFF55FF),
	LEGENDARY(0xFFAA00);

	/** Text color used by screens and messages. */
	public final int color;

	Tier(int color) {
		this.color = color;
	}

	public String serializedName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String langKey() {
		return "absorbaholic.tier." + serializedName();
	}

	public static Optional<Tier> byName(String name) {
		for (Tier t : values()) {
			if (t.serializedName().equals(name)) return Optional.of(t);
		}
		return Optional.empty();
	}
}
