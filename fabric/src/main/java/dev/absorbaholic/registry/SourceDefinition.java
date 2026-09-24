package dev.absorbaholic.registry;

import java.util.List;
import java.util.Optional;

import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.trait.AttributeEntry;
import dev.absorbaholic.trait.BehaviorEntry;
import net.minecraft.resources.Identifier;

/**
 * A fully resolved, valid source (server side): what it matches, how it looks, and its trait and weakness.
 * Built by {@link SourceResolver} from a {@code SourceSpec}; immutable; lives in {@link SourceRegistry} until the next
 * datapack reload. Gametests may construct one directly.
 *
 * @param icon item id override for screens; empty = block item / spawn egg
 * @param color 0xRRGGBB aura color
 */
public record SourceDefinition(Identifier id, SourceTargets targets, Optional<Identifier> icon, int color, Tier tier, int maxLevel,
		Side trait, Side weakness) implements SourceLike {
	/** One half of a source. Lang keys: {@code absorbaholic.trait.<key>} / {@code absorbaholic.weakness.<key>} and {@code .desc}. */
	public record Side(String key, List<AttributeEntry> attributes, List<BehaviorEntry<?>> behaviors) {
		public Side {
			attributes = List.copyOf(attributes);
			behaviors = List.copyOf(behaviors);
		}
	}

	public SourceKind kind() {
		return targets.kind();
	}

	public String traitLangKey() {
		return "absorbaholic.trait." + trait.key();
	}

	public String weaknessLangKey() {
		return "absorbaholic.weakness." + weakness.key();
	}
}
