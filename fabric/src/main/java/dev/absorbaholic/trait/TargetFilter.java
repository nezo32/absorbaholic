package dev.absorbaholic.trait;

import java.util.ArrayList;
import java.util.List;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * An entity-type filter for behavior params: a list (or single string) of entity type ids and {@code #tags}, e.g.
 * {@code ["#minecraft:zombies", "minecraft:husk"]}. Also accepts the words {@code "hostile"} (Enemy mobs),
 * {@code "all_mobs"} (any Mob) and {@code "players"}. Use {@link #CODEC} in params; {@link #ANY} matches everything.
 */
public final class TargetFilter {
	public static final TargetFilter ANY = new TargetFilter(List.of());

	public static final Codec<TargetFilter> CODEC = Codec.either(Codec.STRING, Codec.STRING.listOf())
			.xmap(e -> e.map(List::of, l -> l), l -> l.size() == 1 ? Either.left(l.getFirst()) : Either.<String, List<String>>right(l))
			.comapFlatMap(TargetFilter::parse, f -> f.raw);

	private final List<String> raw;
	private final List<Identifier> ids = new ArrayList<>();
	private final List<TagKey<EntityType<?>>> tags = new ArrayList<>();
	private boolean hostile;
	private boolean allMobs;
	private boolean players;

	private TargetFilter(List<String> raw) {
		this.raw = List.copyOf(raw);
	}

	private static DataResult<TargetFilter> parse(List<String> raw) {
		TargetFilter f = new TargetFilter(raw);
		for (String s : raw) {
			switch (s) {
				case "hostile" -> f.hostile = true;
				case "all_mobs" -> f.allMobs = true;
				case "players" -> f.players = true;
				default -> {
					boolean tag = s.startsWith("#");
					Identifier id = Identifier.tryParse(tag ? s.substring(1) : s);
					if (id == null) return DataResult.error(() -> "bad entity filter entry " + s);
					if (tag) {
						f.tags.add(TagKey.create(Registries.ENTITY_TYPE, id));
					} else if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
						f.ids.add(id);
					} else {
						return DataResult.error(() -> "unknown entity type " + s);
					}
				}
			}
		}
		return DataResult.success(f);
	}

	public boolean isAny() {
		return raw.isEmpty();
	}

	public boolean test(Entity e) {
		if (raw.isEmpty()) return true;
		if (hostile && e instanceof Enemy) return true;
		if (allMobs && e instanceof Mob) return true;
		if (players && e instanceof Player) return true;
		EntityType<?> type = e.getType();
		for (Identifier id : ids) {
			if (BuiltInRegistries.ENTITY_TYPE.getKey(type).equals(id)) return true;
		}
		for (TagKey<EntityType<?>> tag : tags) {
			if (type.builtInRegistryHolder().is(tag)) return true;
		}
		return false;
	}
}
