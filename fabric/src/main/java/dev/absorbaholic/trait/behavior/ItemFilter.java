package dev.absorbaholic.trait.behavior;

import java.util.ArrayList;
import java.util.List;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * An item filter for behavior params: item ids and {@code #item tags}, a single string or a list. Direct ids must
 * exist (else the source is skipped); tags bind lazily. {@link #NONE} matches nothing.
 */
final class ItemFilter {
	static final ItemFilter NONE = new ItemFilter(List.of());

	static final Codec<ItemFilter> CODEC = Codec.either(Codec.STRING, Codec.STRING.listOf())
			.xmap(e -> e.map(List::of, l -> l), l -> l.size() == 1 ? Either.left(l.getFirst()) : Either.<String, List<String>>right(l))
			.comapFlatMap(ItemFilter::parse, f -> f.raw);

	private final List<String> raw;
	private final List<Item> items = new ArrayList<>();
	private final List<TagKey<Item>> tags = new ArrayList<>();

	private ItemFilter(List<String> raw) {
		this.raw = List.copyOf(raw);
	}

	private static DataResult<ItemFilter> parse(List<String> raw) {
		ItemFilter f = new ItemFilter(raw);
		for (String s : raw) {
			boolean tag = s.startsWith("#");
			Identifier id = Identifier.tryParse(tag ? s.substring(1) : s);
			if (id == null) return DataResult.error(() -> "bad item filter entry " + s);
			if (tag) {
				f.tags.add(TagKey.create(Registries.ITEM, id));
			} else if (BuiltInRegistries.ITEM.containsKey(id)) {
				f.items.add(BuiltInRegistries.ITEM.getValue(id));
			} else {
				return DataResult.error(() -> "unknown item " + s);
			}
		}
		return DataResult.success(f);
	}

	boolean isEmpty() {
		return raw.isEmpty();
	}

	boolean test(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (items.contains(stack.getItem())) return true;
		for (TagKey<Item> tag : tags) {
			if (stack.is(tag)) return true;
		}
		return false;
	}
}
