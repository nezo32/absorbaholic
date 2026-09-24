package dev.absorbaholic.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Structural parsing and validation of a source JSON (schema v1, see ARCHITECTURE.md / SPEC). Never throws: a bad
 * file yields {@link Result.Invalid} with every problem found, which the loader logs as one WARN naming the file.
 * Checks that need the game (attribute and behavior type existence, params codecs, target ids existing) happen in
 * the registry layer. Pure (Gson only).
 */
public final class SourceSpecParser {
	/** The outcome for one file. */
	public sealed interface Result {
		record Parsed(SourceSpec spec) implements Result {}

		/** {@code {"disabled": true}}: a datapack removes the source with this id. */
		record Disabled() implements Result {}

		record Invalid(List<String> errors) implements Result {
			public Invalid {
				errors = List.copyOf(errors);
			}
		}
	}

	/** Attribute modifier operations (serialized names of vanilla's AttributeModifier.Operation). */
	public static final Set<String> OPERATIONS = Set.of("add_value", "add_multiplied_base", "add_multiplied_total");

	private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
	private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
	private static final Pattern KEY = Pattern.compile("[a-z0-9_]+");

	private SourceSpecParser() {}

	public static Result parse(JsonElement root) {
		if (root == null || !root.isJsonObject()) return invalid("root must be a JSON object");
		JsonObject obj = root.getAsJsonObject();
		if (obj.has("disabled")) {
			JsonElement d = obj.get("disabled");
			if (!isBoolean(d)) return invalid("\"disabled\" must be a boolean");
			if (d.getAsBoolean()) return new Result.Disabled();
		}
		List<String> errors = new ArrayList<>();

		SourceKind kind = null;
		String kindName = string(obj, "kind", errors, true);
		if (kindName != null) {
			kind = SourceKind.byName(kindName).orElse(null);
			if (kind == null) errors.add("unknown kind \"" + kindName + "\" (block | entity)");
		}

		List<String> targets = new ArrayList<>();
		JsonElement t = obj.get("targets");
		if (t == null || !t.isJsonArray() || t.getAsJsonArray().isEmpty()) {
			errors.add("\"targets\" must be a non-empty array of ids or #tags");
		} else {
			for (JsonElement e : t.getAsJsonArray()) {
				Optional<String> id = isString(e) ? normalizeTarget(e.getAsString()) : Optional.empty();
				if (id.isEmpty()) {
					errors.add("bad target " + e);
				} else {
					targets.add(id.get());
				}
			}
		}

		Optional<String> icon = Optional.empty();
		if (obj.has("icon")) {
			JsonElement i = obj.get("icon");
			Optional<String> id = isString(i) ? normalizeId(i.getAsString()) : Optional.empty();
			if (id.isEmpty()) errors.add("bad icon " + i);
			icon = id;
		}

		int color = ColorMix.DEFAULT;
		if (obj.has("color")) {
			JsonElement c = obj.get("color");
			color = isString(c) ? ColorMix.parseHex(c.getAsString()) : -1;
			if (color < 0) errors.add("\"color\" must be \"#RRGGBB\", got " + c);
		}

		Tier tier = Tier.COMMON;
		if (obj.has("tier")) {
			JsonElement tr = obj.get("tier");
			tier = isString(tr) ? Tier.byName(tr.getAsString()).orElse(null) : null;
			if (tier == null) errors.add("unknown tier " + tr + " (common | uncommon | rare | epic | legendary)");
		}

		int maxLevel = AbsorbCaps.DEFAULT_MAX_LEVEL;
		if (obj.has("max_level")) {
			JsonElement m = obj.get("max_level");
			if (!isInt(m) || m.getAsInt() < AbsorbCaps.MIN_MAX_LEVEL || m.getAsInt() > AbsorbCaps.MAX_MAX_LEVEL) {
				errors.add("\"max_level\" must be an integer " + AbsorbCaps.MIN_MAX_LEVEL + ".." + AbsorbCaps.MAX_MAX_LEVEL + ", got " + m);
			} else {
				maxLevel = m.getAsInt();
			}
		}

		SourceSpec.SideSpec trait = side(obj, "trait", maxLevel, errors);
		SourceSpec.SideSpec weakness = side(obj, "weakness", maxLevel, errors);

		if (!errors.isEmpty()) return new Result.Invalid(errors);
		return new Result.Parsed(new SourceSpec(kind, targets, icon, color, tier, maxLevel, trait, weakness));
	}

	private static SourceSpec.SideSpec side(JsonObject root, String name, int maxLevel, List<String> errors) {
		JsonElement s = root.get(name);
		if (s == null || !s.isJsonObject()) {
			errors.add("\"" + name + "\" must be an object");
			return null;
		}
		JsonObject obj = s.getAsJsonObject();
		String key = string(obj, "key", errors, true);
		if (key != null && !KEY.matcher(key).matches()) errors.add(name + ".key must match [a-z0-9_]+, got \"" + key + "\"");

		List<SourceSpec.AttributeSpec> attributes = new ArrayList<>();
		JsonElement attrs = obj.get("attributes");
		if (attrs != null) {
			if (!attrs.isJsonArray()) {
				errors.add(name + ".attributes must be an array");
			} else {
				int i = 0;
				for (JsonElement a : attrs.getAsJsonArray()) {
					String where = name + ".attributes[" + i++ + "]";
					if (!a.isJsonObject()) {
						errors.add(where + " must be an object");
						continue;
					}
					JsonObject ao = a.getAsJsonObject();
					String attribute = string(ao, "attribute", errors, true);
					Optional<String> attributeId = attribute == null ? Optional.empty() : normalizeId(attribute);
					if (attribute != null && attributeId.isEmpty()) errors.add(where + ": bad attribute id \"" + attribute + "\"");
					String op = string(ao, "operation", errors, true);
					if (op != null && !OPERATIONS.contains(op)) errors.add(where + ": unknown operation \"" + op + "\"");
					Optional<LevelValue> amount = LevelValue.fromJson(ao.get("amount"));
					if (amount.isEmpty()) {
						errors.add(where + ": \"amount\" must be a number or a non-empty number array");
					} else if (amount.get().definedLevels() < maxLevel) {
						errors.add(where + ": \"amount\" array has " + amount.get().definedLevels() + " entries, max_level is " + maxLevel);
					}
					if (attributeId.isPresent() && op != null && OPERATIONS.contains(op) && amount.isPresent()) {
						attributes.add(new SourceSpec.AttributeSpec(attributeId.get(), op, amount.get()));
					}
				}
			}
		}

		List<SourceSpec.BehaviorSpec> behaviors = new ArrayList<>();
		JsonElement behs = obj.get("behaviors");
		if (behs != null) {
			if (!behs.isJsonArray()) {
				errors.add(name + ".behaviors must be an array");
			} else {
				int i = 0;
				for (JsonElement b : behs.getAsJsonArray()) {
					String where = name + ".behaviors[" + i++ + "]";
					if (!b.isJsonObject()) {
						errors.add(where + " must be an object");
						continue;
					}
					JsonObject bo = b.getAsJsonObject();
					String type = string(bo, "type", errors, true);
					Optional<String> typeId = type == null ? Optional.empty() : normalizeId(type);
					if (type != null && typeId.isEmpty()) errors.add(where + ": bad behavior type id \"" + type + "\"");
					if (typeId.isPresent()) {
						JsonObject params = new JsonObject();
						for (Map.Entry<String, JsonElement> e : bo.entrySet()) {
							if (!e.getKey().equals("type")) params.add(e.getKey(), e.getValue().deepCopy());
						}
						behaviors.add(new SourceSpec.BehaviorSpec(typeId.get(), params));
					}
				}
			}
		}
		return key == null ? null : new SourceSpec.SideSpec(key, attributes, behaviors);
	}

	/** "stone" → "minecraft:stone"; "ns:path" kept; anything else (upper case, spaces, …) → empty. */
	public static Optional<String> normalizeId(String raw) {
		if (raw == null || raw.isEmpty()) return Optional.empty();
		int colon = raw.indexOf(':');
		String ns = colon < 0 ? "minecraft" : raw.substring(0, colon);
		String path = colon < 0 ? raw : raw.substring(colon + 1);
		if (!NAMESPACE.matcher(ns).matches() || !PATH.matcher(path).matches()) return Optional.empty();
		return Optional.of(ns + ":" + path);
	}

	/** Like {@link #normalizeId} but keeps a leading '#' (tag reference). */
	public static Optional<String> normalizeTarget(String raw) {
		if (raw != null && raw.startsWith("#")) return normalizeId(raw.substring(1)).map(id -> "#" + id);
		return normalizeId(raw);
	}

	private static String string(JsonObject obj, String key, List<String> errors, boolean required) {
		JsonElement e = obj.get(key);
		if (e == null) {
			if (required) errors.add("missing \"" + key + "\"");
			return null;
		}
		if (!isString(e)) {
			errors.add("\"" + key + "\" must be a string");
			return null;
		}
		return e.getAsString();
	}

	private static boolean isString(JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
	}

	private static boolean isBoolean(JsonElement e) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean();
	}

	private static boolean isInt(JsonElement e) {
		if (e == null || !e.isJsonPrimitive()) return false;
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (!p.isNumber()) return false;
		double d = p.getAsDouble();
		return d == Math.rint(d) && !Double.isInfinite(d);
	}

	private static Result invalid(String error) {
		return new Result.Invalid(List.of(error));
	}
}
