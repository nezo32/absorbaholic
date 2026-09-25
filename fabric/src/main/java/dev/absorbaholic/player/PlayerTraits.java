package dev.absorbaholic.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * Everything a player has absorbed, oldest first (the order decides which source is evicted at the slot cap).
 * Immutable: every change creates a new instance, stored with {@code PlayerData.setTraits}, which fires
 * {@link TraitsChangedCallback}. Persisted with {@link #CODEC} as the {@code absorbaholic:traits} player attachment.
 */
public record PlayerTraits(List<TraitEntry> entries) {
	public static final PlayerTraits EMPTY = new PlayerTraits(List.of());

	public static final Codec<PlayerTraits> CODEC = RecordCodecBuilder.create(i -> i.group(
			TraitEntry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(PlayerTraits::entries)
	).apply(i, PlayerTraits::new));

	public static final StreamCodec<ByteBuf, PlayerTraits> STREAM_CODEC =
			TraitEntry.STREAM_CODEC.apply(ByteBufCodecs.list(256)).map(PlayerTraits::new, PlayerTraits::entries);

	public PlayerTraits {
		entries = List.copyOf(entries);
	}

	public int size() {
		return entries.size();
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public Optional<TraitEntry> get(Identifier source) {
		for (TraitEntry e : entries) {
			if (e.source().equals(source)) return Optional.of(e);
		}
		return Optional.empty();
	}

	/** Replaces the entry of the same source in place (keeps its age), or appends a new one (youngest). */
	public PlayerTraits with(TraitEntry entry) {
		List<TraitEntry> list = new ArrayList<>(entries);
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).source().equals(entry.source())) {
				list.set(i, entry);
				return new PlayerTraits(list);
			}
		}
		list.add(entry);
		return new PlayerTraits(list);
	}

	public PlayerTraits without(Identifier source) {
		List<TraitEntry> list = new ArrayList<>(entries);
		list.removeIf(e -> e.source().equals(source));
		return list.size() == entries.size() ? this : new PlayerTraits(list);
	}

	/** The oldest entry, i.e. the one evicted when a new source arrives at the slot cap. */
	public Optional<TraitEntry> oldest() {
		return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getFirst());
	}

	/** Sum of trait levels (aura strength). */
	public int totalTraitLevels() {
		int sum = 0;
		for (TraitEntry e : entries) sum += e.traitLevel();
		return sum;
	}
}
