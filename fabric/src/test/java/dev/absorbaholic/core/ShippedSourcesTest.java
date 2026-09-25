package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Every source JSON shipped in the mod's datapack passes the structural parser (resolution is gametested). */
class ShippedSourcesTest {
	private static final Path DATA = Path.of("src/main/resources/data");

	@Test
	void everyShippedSourceParses() throws IOException {
		if (!Files.isDirectory(DATA)) return;
		List<String> problems = new ArrayList<>();
		int count = 0;
		try (Stream<Path> walk = Files.walk(DATA)) {
			for (Path file : walk.filter(p -> p.toString().replace('\\', '/').contains("/absorbaholic/source/") && p.toString().endsWith(".json")).toList()) {
				count++;
				SourceSpecParser.Result r = SourceSpecParser.parse(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
				if (r instanceof SourceSpecParser.Result.Invalid(List<String> errors)) problems.add(DATA.relativize(file) + ": " + errors);
			}
		}
		assertTrue(problems.isEmpty(), problems.size() + " of " + count + " shipped sources are invalid:\n" + String.join("\n", problems));
	}
}
