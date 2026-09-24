package dev.absorbaholic.trait;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The shared "when" of behavior params. Embed {@link #FIELDS} in a params codec
 * ({@code Condition.FIELDS.forGetter(Params::condition)}) to accept these optional JSON fields:
 * <ul>
 * <li>{@code "condition"}: a name or a list of names, all must hold; {@code "!name"} negates;</li>
 * <li>{@code "condition_any"}: a list of names, at least one must hold;</li>
 * <li>{@code "condition_blocks"}: block ids / #tags for {@code on_block};</li>
 * <li>{@code "condition_entities"}, {@code "condition_radius"} (default 8): entity type ids / #tags for {@code near_entity}.</li>
 * </ul>
 * No fields = always true. Unknown names fail decoding (the source is skipped with a WARN). Names are in
 * {@link #PREDICATES}; add new ones there (owner: WP-BEH-B).
 */
public final class Condition {
	public static final Condition ALWAYS = new Condition(List.of(), List.of(), List.of(), List.of(), 8.0);

	/** Named predicates. Cheap checks only: conditions are evaluated on periodic ticks and damage events. */
	public static final Map<String, BiPredicate<ServerPlayer, Condition>> PREDICATES = Map.ofEntries(
			Map.entry("wet", (p, c) -> p.isInWaterOrRain()),
			Map.entry("dry", (p, c) -> !p.isInWaterOrRain()),
			Map.entry("in_water", (p, c) -> p.isInWater()),
			Map.entry("underwater", (p, c) -> p.isUnderWater()),
			Map.entry("in_rain", (p, c) -> p.level().isRainingAt(eye(p)) || p.level().isRainingAt(p.blockPosition())),
			Map.entry("in_lava", (p, c) -> p.isInLava()),
			Map.entry("open_sky", (p, c) -> p.level().canSeeSky(eye(p))),
			Map.entry("in_sunlight", (p, c) -> p.level().isBrightOutside() && !p.isInWaterOrRain() && p.level().canSeeSky(eye(p))),
			Map.entry("day", (p, c) -> p.level().isBrightOutside()),
			Map.entry("night", (p, c) -> p.level().isDarkOutside()),
			Map.entry("in_darkness", (p, c) -> p.level().getMaxLocalRawBrightness(eye(p)) <= 4),
			Map.entry("thundering", (p, c) -> p.level().isThundering()),
			Map.entry("hot", (p, c) -> p.level().dimension() == Level.NETHER
					|| p.level().getBiome(p.blockPosition()).value().getBaseTemperature() > 1.0F),
			Map.entry("cold", (p, c) -> p.level().getBiome(p.blockPosition()).value().getBaseTemperature() < 0.15F),
			Map.entry("in_nether", (p, c) -> p.level().dimension() == Level.NETHER),
			Map.entry("in_end", (p, c) -> p.level().dimension() == Level.END),
			Map.entry("in_overworld", (p, c) -> p.level().dimension() == Level.OVERWORLD),
			Map.entry("low_health", (p, c) -> p.getHealth() <= p.getMaxHealth() * 0.3F),
			Map.entry("starving", (p, c) -> p.getFoodData().getFoodLevel() <= 6),
			Map.entry("sneaking", (p, c) -> p.isShiftKeyDown()),
			Map.entry("sprinting", (p, c) -> p.isSprinting()),
			Map.entry("airborne", (p, c) -> !p.onGround() && !p.isInWater() && !p.isInLava() && !p.onClimbable()),
			Map.entry("on_block", Condition::onBlock),
			Map.entry("near_entity", Condition::nearEntity));

	private static final Codec<List<String>> STRING_OR_LIST = Codec.either(Codec.STRING, Codec.STRING.listOf())
			.xmap(e -> e.map(List::of, l -> l), l -> l.size() == 1 ? Either.left(l.getFirst()) : Either.right(l));

	public static final MapCodec<Condition> FIELDS = RecordCodecBuilder.<Condition>mapCodec(i -> i.group(
			STRING_OR_LIST.optionalFieldOf("condition", List.of()).forGetter(c -> c.all),
			Codec.STRING.listOf().optionalFieldOf("condition_any", List.of()).forGetter(c -> c.any),
			STRING_OR_LIST.optionalFieldOf("condition_blocks", List.of()).forGetter(c -> c.blocks),
			STRING_OR_LIST.optionalFieldOf("condition_entities", List.of()).forGetter(c -> c.entities),
			Codec.DOUBLE.optionalFieldOf("condition_radius", 8.0).forGetter(c -> c.radius)
	).apply(i, Condition::new)).validate(Condition::validate);

	private final List<String> all;
	private final List<String> any;
	private final List<String> blocks;
	private final List<String> entities;
	private final double radius;
	private final List<Identifier> blockIds = new ArrayList<>();
	private final List<TagKey<Block>> blockTags = new ArrayList<>();
	private final List<Identifier> entityIds = new ArrayList<>();
	private final List<TagKey<EntityType<?>>> entityTags = new ArrayList<>();

	private Condition(List<String> all, List<String> any, List<String> blocks, List<String> entities, double radius) {
		this.all = List.copyOf(all);
		this.any = List.copyOf(any);
		this.blocks = List.copyOf(blocks);
		this.entities = List.copyOf(entities);
		this.radius = radius;
		for (String b : blocks) {
			if (b.startsWith("#")) {
				Identifier id = Identifier.tryParse(b.substring(1));
				if (id != null) blockTags.add(TagKey.create(Registries.BLOCK, id));
			} else {
				Identifier id = Identifier.tryParse(b);
				if (id != null) blockIds.add(id);
			}
		}
		for (String e : entities) {
			if (e.startsWith("#")) {
				Identifier id = Identifier.tryParse(e.substring(1));
				if (id != null) entityTags.add(TagKey.create(Registries.ENTITY_TYPE, id));
			} else {
				Identifier id = Identifier.tryParse(e);
				if (id != null) entityIds.add(id);
			}
		}
	}

	private DataResult<Condition> validate() {
		List<String> names = new ArrayList<>(all);
		names.addAll(any);
		for (String n : names) {
			String name = n.startsWith("!") ? n.substring(1) : n;
			if (!PREDICATES.containsKey(name)) return DataResult.error(() -> "unknown condition \"" + name + "\"");
		}
		if (radius <= 0 || radius > 32) return DataResult.error(() -> "condition_radius must be in (0, 32]");
		for (String b : blocks) {
			if (Identifier.tryParse(b.startsWith("#") ? b.substring(1) : b) == null) return DataResult.error(() -> "bad condition_blocks entry " + b);
		}
		for (String e : entities) {
			if (Identifier.tryParse(e.startsWith("#") ? e.substring(1) : e) == null) return DataResult.error(() -> "bad condition_entities entry " + e);
		}
		return DataResult.success(this);
	}

	public boolean isAlways() {
		return all.isEmpty() && any.isEmpty();
	}

	/** Server thread. */
	public boolean test(ServerPlayer player) {
		for (String n : all) {
			if (!term(n, player)) return false;
		}
		if (any.isEmpty()) return true;
		for (String n : any) {
			if (term(n, player)) return true;
		}
		return false;
	}

	private boolean term(String n, ServerPlayer player) {
		boolean negate = n.startsWith("!");
		BiPredicate<ServerPlayer, Condition> p = PREDICATES.get(negate ? n.substring(1) : n);
		return p != null && p.test(player, this) != negate;
	}

	private static BlockPos eye(ServerPlayer p) {
		return BlockPos.containing(p.getX(), p.getEyeY(), p.getZ());
	}

	private static boolean onBlock(ServerPlayer p, Condition c) {
		BlockState below = p.level().getBlockState(p.getOnPos());
		BlockState feet = p.level().getBlockState(p.blockPosition()); // snow layers, carpets
		return c.matchesBlock(below) || c.matchesBlock(feet);
	}

	private boolean matchesBlock(BlockState state) {
		for (Identifier id : blockIds) {
			if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(id)) return true;
		}
		for (TagKey<Block> tag : blockTags) {
			if (state.is(tag)) return true;
		}
		return false;
	}

	private static boolean nearEntity(ServerPlayer p, Condition c) {
		ServerLevel level = p.level();
		return !level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(c.radius),
				e -> e != p && e.isAlive() && c.matchesEntity(e)).isEmpty();
	}

	private boolean matchesEntity(LivingEntity e) {
		for (Identifier id : entityIds) {
			if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).equals(id)) return true;
		}
		for (TagKey<EntityType<?>> tag : entityTags) {
			if (e.getType().builtInRegistryHolder().is(tag)) return true;
		}
		return false;
	}
}
