package dev.absorbaholic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.absorbaholic.core.Tier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * en_us / ru_ru parity and coverage. Work packages add their required keys to {@link #REQUIRED} through the lead's
 * lang merge (keys come from scratchpad/lang/&lt;wp&gt;-en.json).
 */
class LangFileTest {
	private static final Path SOURCES = Path.of("src/main/resources/data");
	private static final List<Path> JAVA_ROOTS = List.of(Path.of("src/main/java"), Path.of("src/client/java"));
	/** A string literal that is a complete absorbaholic lang key (not a prefix ending with '.'). */
	private static final Pattern KEY_LITERAL = Pattern.compile("\"((?:absorbaholic|key\\.absorbaholic|key\\.category\\.absorbaholic|death\\.attack\\.absorbaholic)\\.[a-z0-9_.]*[a-z0-9_])(?<!\\.json)\"");

	private static JsonObject en;
	private static JsonObject ru;

	@BeforeAll
	static void load() throws IOException {
		en = read("en_us");
		ru = read("ru_ru");
	}

	private static JsonObject read(String code) throws IOException {
		try (InputStream in = LangFileTest.class.getResourceAsStream("/assets/absorbaholic/lang/" + code + ".json")) {
			assertNotNull(in, code + ".json not on the test classpath");
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader).getAsJsonObject();
			}
		}
	}

	/** Format placeholders (%s, %1$s, …) of a lang value, sorted; %% is a literal percent sign. */
	static List<String> placeholders(String value) {
		Matcher m = Pattern.compile("%(?:(\\d+)\\$)?([a-zA-Z%])").matcher(value);
		List<String> out = new ArrayList<>();
		int next = 1;
		while (m.find()) {
			if (m.group(2).equals("%")) continue;
			out.add((m.group(1) != null ? m.group(1) : String.valueOf(next++)) + "$" + m.group(2));
		}
		out.sort(null);
		return out;
	}

	@Test
	void russianHasExactlyTheEnglishKeys() {
		assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(ru.keySet()), "ru_ru.json key set differs from en_us.json");
	}

	@Test
	void russianPlaceholdersMatchEnglish() {
		for (String key : en.keySet()) {
			if (!ru.has(key)) continue;
			assertEquals(placeholders(en.get(key).getAsString()), placeholders(ru.get(key).getAsString()), "placeholders of " + key);
		}
	}

	@Test
	void noEmptyValuesAndOnlyStringPlaceholders() {
		for (JsonObject file : new JsonObject[] {en, ru}) {
			for (String key : file.keySet()) {
				String v = file.get(key).getAsString();
				assertFalse(v.isBlank(), "blank " + key);
				// vanilla rewrites %d / %.1f to %s; a lone % breaks the whole value
				for (String p : placeholders(v)) assertTrue(p.endsWith("$s"), key + ": only %s placeholders work in lang files: " + v);
				assertFalse(v.replace("%%", "").matches(".*%(?![0-9]*\\$?s).*"), key + ": write %% for a percent sign: " + v);
			}
		}
	}

	@Test
	void tiersPresent() {
		for (Tier t : Tier.values()) assertTrue(en.has(t.langKey()), "missing " + t.langKey());
	}

	/** Every complete key literal in Java sources exists in en_us (keys built at runtime are checked by dedicated tests). */
	@Test
	void everyKeyLiteralInSourcesExists() throws IOException {
		List<String> missing = new ArrayList<>();
		for (Path root : JAVA_ROOTS) {
			if (!Files.isDirectory(root)) continue;
			try (Stream<Path> walk = Files.walk(root)) {
				for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
					for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
						String s = line.strip();
						if (s.startsWith("*") || s.startsWith("/*") || s.startsWith("//")) continue;
						Matcher m = KEY_LITERAL.matcher(line);
						while (m.find()) {
							if (!en.has(m.group(1))) missing.add(file.getFileName() + ": " + m.group(1));
						}
					}
				}
			}
		}
		assertTrue(missing.isEmpty(), "lang keys used in code but missing from en_us:\n" + String.join("\n", missing));
	}

	/** Every trait / weakness key of every shipped source JSON has a name and a description in both languages. */
	@Disabled("LEAD: enable once the designer's lang fragments are merged into en_us / ru_ru")
	@Test
	void everySourceKeyHasLang() throws IOException {
		if (!Files.isDirectory(SOURCES)) return;
		List<String> missing = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			for (Path file : walk.filter(p -> p.toString().replace('\\', '/').contains("/absorbaholic/source/") && p.toString().endsWith(".json")).toList()) {
				JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
				if (!root.isJsonObject() || root.getAsJsonObject().has("disabled")) continue;
				for (String side : new String[] {"trait", "weakness"}) {
					JsonObject o = root.getAsJsonObject().getAsJsonObject(side);
					if (o == null || !o.has("key")) continue;
					String base = "absorbaholic." + side + "." + o.get("key").getAsString();
					for (String key : new String[] {base, base + ".desc"}) {
						if (!en.has(key) || !ru.has(key)) missing.add(file.getFileName() + ": " + key);
					}
				}
			}
		}
		assertTrue(missing.isEmpty(), "source lang keys missing:\n" + String.join("\n", missing));
	}
}
