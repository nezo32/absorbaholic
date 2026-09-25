package dev.absorbaholic.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.absorbaholic.core.MutationRoll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (tester): no hardcoded player-facing text in the Java sources, and the runtime-built keys of the absorb
 * feedback exist in both languages (en_us / ru_ru parity itself is {@code LangFileTest}).
 */
class LocalizationRequirementsTest {
	private static final List<Path> ROOTS = List.of(Path.of("src/main/java"), Path.of("src/client/java"));
	private static JsonObject en;
	private static JsonObject ru;

	@BeforeAll
	static void load() throws IOException {
		en = read("en_us");
		ru = read("ru_ru");
	}

	private static JsonObject read(String code) throws IOException {
		try (InputStream in = LocalizationRequirementsTest.class.getResourceAsStream("/assets/absorbaholic/lang/" + code + ".json")) {
			assertNotNull(in, code);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	/** Java source lines, without comments (line and block). */
	private static List<String[]> codeLines() throws IOException {
		List<String[]> out = new ArrayList<>();
		for (Path root : ROOTS) {
			try (Stream<Path> walk = Files.walk(root)) {
				for (Path f : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
					boolean block = false;
					for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
						String s = line.strip();
						if (block) {
							if (s.contains("*/")) block = false;
							continue;
						}
						if (s.startsWith("/*")) {
							block = !s.contains("*/");
							continue;
						}
						if (s.startsWith("//") || s.startsWith("*")) continue;
						out.add(new String[] {f.getFileName().toString(), line});
					}
				}
			}
		}
		return out;
	}

	/**
	 * Text components built from a string LITERAL that contains a word (three letters in a row): Component.literal,
	 * Component.nullToEmpty, Tooltip / Button / sendSystemMessage / drawString with a literal. Ids and player names
	 * (Component.literal(id.toString())) are fine.
	 */
	@Test
	void noHardcodedPlayerFacingText() throws IOException {
		Pattern literal = Pattern.compile(
				"(Component\\.literal|Component\\.nullToEmpty|Component\\.translatableWithFallback\\([^,]+,|sendSystemMessage|sendSuccess|sendFailure"
						+ "|sendFeedback|sendError|drawString\\([^,]+,|drawCenteredString\\([^,]+,|Tooltip\\.create|Button\\.builder)\\s*\\(?\\s*\"([^\"]*)\"");
		Pattern word = Pattern.compile("\\p{L}{3,}");
		List<String> found = new ArrayList<>();
		for (String[] l : codeLines()) {
			Matcher m = literal.matcher(l[1]);
			while (m.find()) {
				if (word.matcher(m.group(2)).find()) found.add(l[0] + ": " + l[1].strip());
			}
		}
		assertTrue(found.isEmpty(), "hardcoded player-facing text:\n" + String.join("\n", found));
	}

	/** The absorb title and the per-outcome subtitle / broadcast keys the code builds at runtime exist in both files. */
	@Test
	void absorbFeedbackKeysExistInBothLanguages() {
		List<String> keys = new ArrayList<>(List.of("absorbaholic.absorbed.title", "absorbaholic.absorbed.title.short",
				"absorbaholic.absorbed.actionbar", "absorbaholic.absorbed.actionbar.pure", "absorbaholic.absorbed.subtitle.joined"));
		for (MutationRoll.Outcome o : MutationRoll.Outcome.values()) {
			if (!o.isSpecial()) continue;
			String n = o.name().toLowerCase(Locale.ROOT);
			keys.add("absorbaholic.absorbed.subtitle." + n);
			keys.add("absorbaholic.announce." + n);
		}
		for (String k : keys) {
			assertTrue(en.has(k), "en_us lacks " + k);
			assertTrue(ru.has(k), "ru_ru lacks " + k);
		}
		assertEquals("🧬 Absorbed: %s", en.get("absorbaholic.absorbed.title").getAsString(), "brief title");
		assertTrue(ru.get("absorbaholic.absorbed.title").getAsString().startsWith("🧬 "), "ru title keeps the 🧬");
		assertTrue(ru.get("absorbaholic.absorbed.title").getAsString().contains("%s"), "ru title names the source");
	}

	/** Russian is actually translated: no value identical to English except pure formatting / symbols / names. */
	@Test
	void russianIsTranslated() {
		Pattern latinWord = Pattern.compile("[A-Za-z]{4,}");
		List<String> same = new ArrayList<>();
		for (String k : en.keySet()) {
			String e = en.get(k).getAsString();
			String r = ru.has(k) ? ru.get(k).getAsString() : null;
			if (r != null && r.equals(e) && latinWord.matcher(e.replaceAll("%\\d*\\$?s", "")).find() && !e.contains("Absorbaholic")) same.add(k + " = " + e);
		}
		assertTrue(same.isEmpty(), "ru_ru values left in English:\n" + String.join("\n", same));
	}

	/** Every lang key of the "absorbaholic." namespace that the Create World toggle / commands need is present. */
	@Test
	void createWorldToggleIsLocalized() {
		assertTrue(en.has("absorbaholic.createWorld.toggle") && ru.has("absorbaholic.createWorld.toggle"));
		assertEquals("Absorbaholic Mode", en.get("absorbaholic.createWorld.toggle").getAsString());
	}
}
