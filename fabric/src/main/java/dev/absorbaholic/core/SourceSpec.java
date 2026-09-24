package dev.absorbaholic.core;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;

/**
 * One source JSON ({@code data/<ns>/absorbaholic/source/<path>.json}) after structural parsing by
 * {@link SourceSpecParser}: ids are still strings, behavior params still raw JSON. The registry layer resolves it
 * against the game (attributes, behavior types, params codecs) into a {@code SourceDefinition}. Pure.
 *
 * @param targets block / entity type ids and {@code #tag} ids, as written
 * @param icon optional item id
 * @param color 0xRRGGBB aura color
 */
public record SourceSpec(SourceKind kind, List<String> targets, Optional<String> icon, int color, Tier tier, int maxLevel,
		SideSpec trait, SideSpec weakness) {
	public SourceSpec {
		targets = List.copyOf(targets);
	}

	/** The trait or the weakness half of a source. {@code key} names lang keys {@code absorbaholic.trait|weakness.<key>}. */
	public record SideSpec(String key, List<AttributeSpec> attributes, List<BehaviorSpec> behaviors) {
		public SideSpec {
			attributes = List.copyOf(attributes);
			behaviors = List.copyOf(behaviors);
		}
	}

	/** {@code {"attribute": "minecraft:movement_speed", "operation": "add_multiplied_total", "amount": [-0.1, -0.2]}} */
	public record AttributeSpec(String attribute, String operation, LevelValue amount) {}

	/** {@code {"type": "absorbaholic:damage_multiplier", ...params}}; {@code params} is the object without "type". */
	public record BehaviorSpec(String type, JsonObject params) {}
}
