package dev.absorbaholic.registry;

import dev.absorbaholic.Absorbaholic;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

/** Our tags. {@code absorbaholic:unabsorbable} always wins over any source entry. */
public final class AbsorbTags {
	public static final TagKey<Block> UNABSORBABLE_BLOCKS = TagKey.create(Registries.BLOCK, Absorbaholic.id("unabsorbable"));
	public static final TagKey<EntityType<?>> UNABSORBABLE_ENTITIES = TagKey.create(Registries.ENTITY_TYPE, Absorbaholic.id("unabsorbable"));
	/** Blocks protected near the world floor / Nether roof ({@code AbsorbRules.isProtected}); ships with minecraft:bedrock. */
	public static final TagKey<Block> PROTECTED_BLOCKS = TagKey.create(Registries.BLOCK, Absorbaholic.id("bedrock_protected"));

	private AbsorbTags() {}
}
