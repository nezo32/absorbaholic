package dev.absorbaholic.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotifySettingsTest {
	@TempDir
	Path dir;

	@Test
	void missingFileIsDefaultAndNotCreated() {
		Path file = dir.resolve("absorbaholic.json");
		assertEquals(NotifySettings.DEFAULT, NotifySettings.load(file));
		assertFalse(Files.exists(file));
		assertEquals(new NotifySettings(true, true), NotifySettings.DEFAULT);
	}

	@Test
	void saveLoadRoundTripsAllCombinations() throws IOException {
		Path file = dir.resolve("absorbaholic.json");
		for (boolean sound : new boolean[] {true, false}) {
			for (boolean message : new boolean[] {true, false}) {
				NotifySettings s = new NotifySettings(sound, message);
				s.save(file);
				assertEquals(s, NotifySettings.load(file), s.toString());
			}
		}
		new NotifySettings(false, true).save(file);
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.contains("\"notifySound\": false"), text);
		assertTrue(text.contains("\"notifyMessage\": true"), text);
	}

	@Test
	void toJsonFormat() {
		assertEquals("{\n  \"notifySound\": true,\n  \"notifyMessage\": false\n}\n", new NotifySettings(true, false).toJson());
	}

	@Test
	void saveCreatesParentsOverwritesAndLeavesNoTmp() throws IOException {
		Path file = dir.resolve("a/b/config/absorbaholic.json");
		new NotifySettings(false, false).save(file);
		assertEquals(new NotifySettings(false, false), NotifySettings.load(file));
		new NotifySettings(true, false).save(file);
		assertEquals(new NotifySettings(true, false), NotifySettings.load(file));
		try (Stream<Path> files = Files.list(file.getParent())) {
			List<String> names = files.map(p -> p.getFileName().toString()).toList();
			assertEquals(List.of("absorbaholic.json"), names);
		}
	}

	@Test
	void corruptInputIsDefaultAndNotRewritten() throws IOException {
		Path file = dir.resolve("absorbaholic.json");
		List<byte[]> inputs = List.of(
				"not json".getBytes(StandardCharsets.UTF_8),
				new byte[0],
				"[]".getBytes(StandardCharsets.UTF_8),
				"null".getBytes(StandardCharsets.UTF_8),
				"{\"notifySound\":".getBytes(StandardCharsets.UTF_8),
				new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0xC3, 0x28, (byte) 0x80, 0x7B, 0x01, (byte) 0xFA});
		for (byte[] bytes : inputs) {
			Files.write(file, bytes);
			assertEquals(NotifySettings.DEFAULT, NotifySettings.load(file), "input " + new String(bytes, StandardCharsets.ISO_8859_1));
			assertArrayEquals(bytes, Files.readAllBytes(file), "load must not rewrite the file");
		}
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse(null));
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse("\"x\""));
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse("true"));
	}

	@Test
	void directoryInsteadOfFileIsDefault() throws IOException {
		Path file = dir.resolve("absorbaholic.json");
		Files.createDirectories(file);
		assertEquals(NotifySettings.DEFAULT, NotifySettings.load(file));
	}

	@Test
	void partialAndWrongTypes() {
		assertEquals(new NotifySettings(false, true), NotifySettings.parse("{\"notifySound\":false}"));
		assertEquals(new NotifySettings(true, false), NotifySettings.parse("{\"notifyMessage\":false}"));
		assertEquals(new NotifySettings(true, true), NotifySettings.parse("{\"notifySound\":\"no\",\"notifyMessage\":0}"));
		assertEquals(new NotifySettings(true, true), NotifySettings.parse("{\"notifySound\":\"false\",\"notifyMessage\":null}"));
		assertEquals(new NotifySettings(true, true), NotifySettings.parse("{\"notifySound\":{},\"notifyMessage\":[false]}"));
		assertEquals(new NotifySettings(false, false),
				NotifySettings.parse("{\"notifySound\":false,\"notifyMessage\":false,\"extra\":1,\"other\":{\"a\":false}}"));
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse("{}"));
	}

	@Test
	void withersAreImmutableAndJsonRoundTrips() {
		NotifySettings base = NotifySettings.DEFAULT;
		NotifySettings noSound = base.withSound(false);
		NotifySettings noMessage = base.withMessage(false);
		assertEquals(new NotifySettings(true, true), base);
		assertEquals(new NotifySettings(false, true), noSound);
		assertEquals(new NotifySettings(true, false), noMessage);
		assertEquals(new NotifySettings(false, false), noSound.withMessage(false));
		assertNotSame(base, base.withSound(true));
		for (NotifySettings s : List.of(base, noSound, noMessage, new NotifySettings(false, false))) {
			assertEquals(s, NotifySettings.parse(s.toJson()));
		}
	}
}
