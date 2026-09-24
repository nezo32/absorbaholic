package dev.absorbaholic.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * A level-scaled number from source JSON: either a single number multiplied by the level ({@code 0.5} → 0.5, 1.0,
 * 1.5 …) or an array indexed by level - 1 ({@code [0.75, 0.5, 0.3]}). Levels above an array's length use its last
 * entry; level 0 (inactive, e.g. the weakness of a pure entry) is always 0.
 *
 * <p>Pure (Gson + DataFixerUpper only). {@link #CODEC} is what behavior params codecs use for level-scaled fields;
 * while {@link #collect} runs on the current thread, every decoded value is recorded so the source resolver can
 * check array lengths against the source's max_level without each behavior doing it.
 */
public sealed interface LevelValue {
	/** Value at {@code level} (1-based); 0 for level &lt;= 0. */
	double at(int level);

	/** Rounded {@link #at}. */
	default int atInt(int level) {
		return (int) Math.round(at(level));
	}

	/** Number of levels explicitly defined: array length, or {@link Integer#MAX_VALUE} for a scaled number. */
	int definedLevels();

	/** {@code perLevel × level}. */
	record Scaled(double perLevel) implements LevelValue {
		@Override
		public double at(int level) {
			return level <= 0 ? 0.0 : perLevel * level;
		}

		@Override
		public int definedLevels() {
			return Integer.MAX_VALUE;
		}
	}

	/** {@code values[min(level, length) - 1]}. Never empty. */
	record PerLevel(List<Double> values) implements LevelValue {
		public PerLevel {
			if (values.isEmpty()) throw new IllegalArgumentException("empty level array");
			values = List.copyOf(values);
		}

		@Override
		public double at(int level) {
			if (level <= 0) return 0.0;
			return values.get(Math.min(level, values.size()) - 1);
		}

		@Override
		public int definedLevels() {
			return values.size();
		}
	}

	static LevelValue scaled(double perLevel) {
		return new Scaled(perLevel);
	}

	static LevelValue perLevel(double... values) {
		return new PerLevel(Arrays.stream(values).boxed().toList());
	}

	/** Constant (same value at every level &gt;= 1); a one-element array. */
	static LevelValue constant(double value) {
		return perLevel(value);
	}

	/** Parses a JSON number or a non-empty array of numbers; empty otherwise. */
	static Optional<LevelValue> fromJson(JsonElement json) {
		if (json == null) return Optional.empty();
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
			return Optional.of(new Scaled(json.getAsDouble()));
		}
		if (json.isJsonArray()) {
			JsonArray array = json.getAsJsonArray();
			if (array.isEmpty()) return Optional.empty();
			List<Double> values = new ArrayList<>(array.size());
			for (JsonElement e : array) {
				if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return Optional.empty();
				values.add(e.getAsDouble());
			}
			return Optional.of(new PerLevel(values));
		}
		return Optional.empty();
	}

	/** Number or non-empty number array; records decoded values while {@link #collect} is active. */
	Codec<LevelValue> CODEC = Codec.either(Codec.DOUBLE, Codec.DOUBLE.listOf())
			.comapFlatMap(either -> either.map(
							d -> DataResult.success((LevelValue) new Scaled(d)),
							list -> list.isEmpty()
									? DataResult.<LevelValue>error(() -> "level array must not be empty")
									: DataResult.success((LevelValue) new PerLevel(list))),
					value -> switch (value) {
						case Scaled s -> Either.left(s.perLevel());
						case PerLevel p -> Either.right(p.values());
					})
			.xmap(Collector::record, v -> v);

	/** Result of {@link #collect}: the produced value and every LevelValue decoded while producing it. */
	record Collected<T>(T value, List<LevelValue> levelValues) {}

	/** Runs {@code action} (e.g. a codec parse) and returns the LevelValues decoded meanwhile on this thread. Re-entrant. */
	static <T> Collected<T> collect(Supplier<T> action) {
		List<LevelValue> outer = Collector.CURRENT.get();
		List<LevelValue> mine = new ArrayList<>();
		Collector.CURRENT.set(mine);
		try {
			return new Collected<>(action.get(), List.copyOf(mine));
		} finally {
			if (outer != null) outer.addAll(mine);
			Collector.CURRENT.set(outer);
		}
	}

	/** Thread-local sink of {@link #collect}. Not API. */
	final class Collector {
		private static final ThreadLocal<List<LevelValue>> CURRENT = new ThreadLocal<>();

		private Collector() {}

		static LevelValue record(LevelValue value) {
			List<LevelValue> sink = CURRENT.get();
			if (sink != null) sink.add(value);
			return value;
		}
	}
}
