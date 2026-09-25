package dev.absorbaholic.registry;

import java.util.List;
import java.util.Optional;

import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.trait.AttributeEntry;
import dev.absorbaholic.trait.BehaviorEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * A fully resolved, valid source (server side): what it matches, how it looks, and its trait and weakness.
 * Built by {@link SourceResolver} from a {@code SourceSpec}; immutable; lives in {@link SourceRegistry} until the next
 * datapack reload. Gametests may construct one directly.
 *
 * @param nameKey translation key of the source's display name (see {@link #defaultNameKey})
 * @param icon item id override for screens; empty = block item / spawn egg
 * @param color 0xRRGGBB aura color
 */
public record SourceDefinition(Identifier id, SourceTargets targets, String nameKey, Optional<Identifier> icon, int color, Tier tier,
		int maxLevel, Side trait, Side weakness) implements SourceLike {
	/** One half of a source. Lang keys: {@code absorbaholic.trait.<key>} / {@code absorbaholic.weakness.<key>} and {@code .desc}. */
	public record Side(String key, List<AttributeEntry> attributes, List<BehaviorEntry<?>> behaviors) {
		public Side {
			attributes = List.copyOf(attributes);
			behaviors = List.copyOf(behaviors);
		}
	}

	/** With the {@linkplain #defaultNameKey default display name}. */
	public SourceDefinition(Identifier id, SourceTargets targets, Optional<Identifier> icon, int color, Tier tier, int maxLevel,
			Side trait, Side weakness) {
		this(id, targets, defaultNameKey(id, targets), icon, color, tier, maxLevel, trait, weakness);
	}

	/**
	 * The display name of a source without an explicit {@code "name"}: the vanilla description id of its single direct
	 * target ({@code block.minecraft.obsidian}, {@code entity.minecraft.blaze}); otherwise (several targets, or a tag)
	 * {@code absorbaholic.source.<path>} (a '/' in the path becomes '.').
	 */
	public static String defaultNameKey(Identifier id, SourceTargets targets) {
		if (targets.ids().size() == 1 && targets.tags().isEmpty()) {
			Identifier target = targets.ids().getFirst();
			if (targets.kind() == SourceKind.BLOCK) {
				Optional<String> block = BuiltInRegistries.BLOCK.getOptional(target).map(b -> b.getDescriptionId());
				if (block.isPresent()) return block.get();
			} else {
				Optional<String> entity = BuiltInRegistries.ENTITY_TYPE.getOptional(target).map(e -> e.getDescriptionId());
				if (entity.isPresent()) return entity.get();
			}
		}
		return "absorbaholic.source." + id.getPath().replace('/', '.');
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
