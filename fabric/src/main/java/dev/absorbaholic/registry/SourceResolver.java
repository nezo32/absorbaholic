package dev.absorbaholic.registry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.absorbaholic.core.LevelValue;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.SourceSpec;
import dev.absorbaholic.trait.AttributeEntry;
import dev.absorbaholic.trait.BehaviorEntry;
import dev.absorbaholic.trait.BehaviorRegistry;
import dev.absorbaholic.trait.BehaviorType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Turns a structurally valid {@link SourceSpec} into a {@link SourceDefinition} against the game's registries:
 * <ul>
 * <li>targets → direct ids of the kind's registry and tag ids. Unknown direct ids are dropped with a warning; no
 * target left is an error. Tags are not checked here (they bind after the reload listeners run).</li>
 * <li>attribute ids → {@code BuiltInRegistries.ATTRIBUTE} holders the player actually has; operation names →
 * {@link AttributeModifier.Operation}.</li>
 * <li>behavior type ids → {@link BehaviorRegistry}; params → {@code type.codec().codec().parse(ops, params)} inside
 * {@link LevelValue#collect}, and every decoded level array must cover max_level. Params the codec does not know
 * are reported as warnings (they are ignored).</li>
 * <li>icon → an existing item (unknown: warning, dropped); name → the explicit key or
 * {@link SourceDefinition#defaultNameKey}.</li>
 * </ul>
 * Errors make the source invalid (the loader skips it); warnings don't. Never throws. Thread-safe (reads frozen
 * registries and the init-time {@link BehaviorRegistry} only).
 */
public final class SourceResolver {
	/** Prefix of the error for a behavior type that is not registered (the gametests recognise it by this). */
	public static final String UNKNOWN_BEHAVIOR = "unknown behavior type ";

	private SourceResolver() {}

	/** The definition, or null with every problem passed to {@code errors}. Params are decoded with plain {@link JsonOps}. */
	public static @Nullable SourceDefinition resolve(Identifier id, SourceSpec spec, Consumer<String> errors) {
		return resolve(id, spec, JsonOps.INSTANCE, errors, warning -> {});
	}

	/**
	 * The definition, or null with every problem passed to {@code errors}. {@code ops} decodes behavior params (the
	 * loader passes {@code RegistryOps} over the reloading registries, so a params codec may also reference
	 * data-driven registry entries); {@code warnings} receives non-fatal problems.
	 */
	public static @Nullable SourceDefinition resolve(Identifier id, SourceSpec spec, DynamicOps<JsonElement> ops,
			Consumer<String> errors, Consumer<String> warnings) {
		List<String> found = new ArrayList<>();
		try {
			SourceTargets targets = targets(spec, found::add, warnings);
			Optional<Identifier> icon = icon(spec, warnings);
			SourceDefinition.Side trait = side("trait", spec.trait(), spec.maxLevel(), ops, found::add, warnings);
			SourceDefinition.Side weakness = side("weakness", spec.weakness(), spec.maxLevel(), ops, found::add, warnings);
			if (found.isEmpty()) {
				String name = spec.name().orElseGet(() -> SourceDefinition.defaultNameKey(id, targets));
				return new SourceDefinition(id, targets, name, icon, spec.color(), spec.tier(), spec.maxLevel(), trait, weakness);
			}
		} catch (RuntimeException e) {
			found.add("internal error while resolving: " + e);
		}
		found.forEach(errors);
		return null;
	}

	/** Convenience for tests: resolve with plain JsonOps and collect the errors (empty = valid). */
	public static List<String> errorsOf(Identifier id, SourceSpec spec) {
		List<String> errors = new ArrayList<>();
		resolve(id, spec, errors::add);
		return errors;
	}

	/** Convenience for tests: resolve with plain JsonOps and collect the warnings. */
	public static List<String> warningsOf(Identifier id, SourceSpec spec) {
		List<String> warnings = new ArrayList<>();
		resolve(id, spec, JsonOps.INSTANCE, error -> {}, warnings::add);
		return warnings;
	}

	/** Behavior type ids used by {@code spec} that are not registered (in order of appearance, no duplicates). */
	public static Set<Identifier> unknownBehaviorTypes(SourceSpec spec) {
		Set<Identifier> unknown = new LinkedHashSet<>();
		for (SourceSpec.SideSpec side : List.of(spec.trait(), spec.weakness())) {
			for (SourceSpec.BehaviorSpec b : side.behaviors()) {
				Identifier type = Identifier.tryParse(b.type());
				if (type != null && BehaviorRegistry.get(type).isEmpty()) unknown.add(type);
			}
		}
		return unknown;
	}

	private static SourceTargets targets(SourceSpec spec, Consumer<String> errors, Consumer<String> warnings) {
		Set<Identifier> ids = new LinkedHashSet<>();
		Set<Identifier> tags = new LinkedHashSet<>();
		String registry = spec.kind() == SourceKind.BLOCK ? "block" : "entity type";
		for (String target : spec.targets()) {
			boolean tag = target.startsWith("#");
			Identifier parsed = Identifier.tryParse(tag ? target.substring(1) : target);
			if (parsed == null) {
				errors.accept("bad target \"" + target + "\"");
			} else if (tag) {
				tags.add(parsed);
			} else if (spec.kind() == SourceKind.BLOCK ? !BuiltInRegistries.BLOCK.containsKey(parsed) : !BuiltInRegistries.ENTITY_TYPE.containsKey(parsed)) {
				warnings.accept("unknown " + registry + " \"" + parsed + "\" in targets, ignored");
			} else {
				if (spec.kind() == SourceKind.ENTITY && !isLiving(parsed)) {
					warnings.accept("entity type \"" + parsed + "\" is not a living entity and can never be absorbed");
				}
				ids.add(parsed);
			}
		}
		if (ids.isEmpty() && tags.isEmpty()) errors.accept("no valid targets left (every " + registry + " id is unknown)");
		return new SourceTargets(spec.kind(), List.copyOf(ids), List.copyOf(tags));
	}

	/** Only living entities have default attributes (and only they can be absorbed). */
	private static boolean isLiving(Identifier id) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
		return DefaultAttributes.hasSupplier(type);
	}

	private static Optional<Identifier> icon(SourceSpec spec, Consumer<String> warnings) {
		if (spec.icon().isEmpty()) return Optional.empty();
		Identifier icon = Identifier.tryParse(spec.icon().get());
		if (icon == null || !BuiltInRegistries.ITEM.containsKey(icon) || BuiltInRegistries.ITEM.getValue(icon) == Items.AIR) {
			warnings.accept("unknown icon item \"" + spec.icon().get() + "\", using the default icon");
			return Optional.empty();
		}
		return Optional.of(icon);
	}

	private static SourceDefinition.Side side(String name, SourceSpec.SideSpec spec, int maxLevel, DynamicOps<JsonElement> ops,
			Consumer<String> errors, Consumer<String> warnings) {
		AttributeSupplier player = DefaultAttributes.getSupplier(EntityTypes.PLAYER);
		List<AttributeEntry> attributes = new ArrayList<>();
		for (int i = 0; i < spec.attributes().size(); i++) {
			SourceSpec.AttributeSpec a = spec.attributes().get(i);
			String where = name + ".attributes[" + i + "]";
			Identifier attributeId = Identifier.tryParse(a.attribute());
			Optional<Holder.Reference<Attribute>> holder = attributeId == null ? Optional.empty() : BuiltInRegistries.ATTRIBUTE.get(attributeId);
			AttributeModifier.Operation operation = operation(a.operation());
			if (holder.isEmpty()) {
				errors.accept(where + ": unknown attribute \"" + a.attribute() + "\"");
			} else if (!player.hasAttribute(holder.get())) {
				errors.accept(where + ": players have no attribute \"" + a.attribute() + "\"");
			}
			if (operation == null) errors.accept(where + ": unknown operation \"" + a.operation() + "\"");
			if (a.amount().definedLevels() < maxLevel) {
				errors.accept(where + ": \"amount\" array has " + a.amount().definedLevels() + " entries, max_level is " + maxLevel);
			}
			if (holder.isPresent() && operation != null) attributes.add(new AttributeEntry(holder.get(), operation, a.amount()));
		}

		List<BehaviorEntry<?>> behaviors = new ArrayList<>();
		for (int i = 0; i < spec.behaviors().size(); i++) {
			SourceSpec.BehaviorSpec b = spec.behaviors().get(i);
			String where = name + ".behaviors[" + i + "]";
			Identifier typeId = Identifier.tryParse(b.type());
			Optional<BehaviorType<?>> type = typeId == null ? Optional.empty() : BehaviorRegistry.get(typeId);
			if (type.isEmpty()) {
				errors.accept(where + ": " + UNKNOWN_BEHAVIOR + b.type());
				continue;
			}
			BehaviorEntry<?> entry = decode(type.get(), b, where, maxLevel, ops, errors, warnings);
			if (entry != null) behaviors.add(entry);
		}
		return new SourceDefinition.Side(spec.key(), attributes, behaviors);
	}

	private static AttributeModifier.@Nullable Operation operation(String name) {
		for (AttributeModifier.Operation op : AttributeModifier.Operation.values()) {
			if (op.getSerializedName().equals(name)) return op;
		}
		return null;
	}

	/** Decodes one behavior's params; null (with errors) if they don't decode or a level array is too short. */
	private static <P> @Nullable BehaviorEntry<P> decode(BehaviorType<P> type, SourceSpec.BehaviorSpec spec, String where, int maxLevel,
			DynamicOps<JsonElement> ops, Consumer<String> errors, Consumer<String> warnings) {
		String label = where + " (" + type.id() + ")";
		Set<String> known = type.codec().keys(JsonOps.INSTANCE)
				.map(k -> k.isJsonPrimitive() ? k.getAsString() : k.toString())
				.collect(Collectors.toSet());
		for (String key : spec.params().keySet()) {
			if (!known.contains(key)) warnings.accept(label + ": unknown param \"" + key + "\" ignored");
		}

		LevelValue.Collected<DataResult<P>> decoded;
		try {
			decoded = LevelValue.collect(() -> type.codec().codec().parse(ops, spec.params()));
		} catch (RuntimeException e) {
			errors.accept(label + ": params could not be decoded: " + e);
			return null;
		}
		Optional<DataResult.Error<P>> error = decoded.value().error();
		if (error.isPresent()) {
			errors.accept(label + ": " + error.get().message());
			return null;
		}
		boolean ok = true;
		for (LevelValue value : decoded.levelValues()) {
			if (value.definedLevels() < maxLevel) {
				errors.accept(label + ": a level array has " + value.definedLevels() + " entries, max_level is " + maxLevel);
				ok = false;
			}
		}
		Optional<P> params = decoded.value().result();
		if (!ok || params.isEmpty()) return null;
		return new BehaviorEntry<>(type, params.get());
	}
}
