package dev.absorbaholic.acceptance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (tester): the common (main) code never references client-only classes, so the mod loads on a dedicated
 * server. Scans the compiled main classes' constant pools (class refs use '/', {@code @Mixin(targets=...)} strings use
 * '.') for client packages, and checks the main mixin config and entrypoints.
 */
class DedicatedServerSafetyTest {
	private static final Path MAIN_CLASSES = Path.of("build/classes/java/main");
	private static final List<String> CLIENT_ONLY = List.of(
			"net/minecraft/client/", "net.minecraft.client.",
			"com/mojang/blaze3d/", "com.mojang.blaze3d.",
			"net/fabricmc/fabric/api/client/", "net.fabricmc.fabric.api.client.",
			"com/terraformersmc/modmenu/",
			"dev/absorbaholic/client/", "dev.absorbaholic.client.");

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	@Test
	void mainClassesReferenceNoClientClasses() throws IOException {
		assertTrue(Files.isDirectory(MAIN_CLASSES), "compiled main classes not found at " + MAIN_CLASSES.toAbsolutePath());
		List<String> bad = new ArrayList<>();
		int n = 0;
		try (Stream<Path> walk = Files.walk(MAIN_CLASSES)) {
			for (Path f : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
				n++;
				byte[] bytes = Files.readAllBytes(f);
				for (String prefix : CLIENT_ONLY) {
					if (contains(bytes, prefix.getBytes(StandardCharsets.UTF_8))) bad.add(MAIN_CLASSES.relativize(f) + " -> " + prefix);
				}
			}
		}
		assertTrue(n > 100, "only " + n + " main classes found; is the path right?");
		assertTrue(bad.isEmpty(), "client-only references in main classes:\n" + String.join("\n", bad));
	}

	@Test
	void mainMixinConfigHasNoClientMixins() throws IOException {
		JsonObject cfg = JsonParser.parseString(Files.readString(Path.of("src/main/resources/absorbaholic.mixins.json"))).getAsJsonObject();
		assertFalse(cfg.has("client") && !cfg.getAsJsonArray("client").isEmpty(), "client mixins belong in absorbaholic.client.mixins.json");
		assertTrue(cfg.get("package").getAsString().startsWith("dev.absorbaholic.mixin"));
		for (JsonElement m : cfg.getAsJsonArray("mixins")) {
			Path src = Path.of("src/main/java/dev/absorbaholic/mixin/" + m.getAsString() + ".java");
			assertTrue(Files.exists(src), "common mixin " + m + " lives in the main source set");
		}
	}

	@Test
	void clientEntrypointsAndMixinsAreClientScoped() throws IOException {
		JsonObject mod = JsonParser.parseString(Files.readString(Path.of("src/main/resources/fabric.mod.json"))).getAsJsonObject();
		assertTrue(mod.get("environment").getAsString().equals("*"), "mod loads on both sides");
		JsonObject ep = mod.getAsJsonObject("entrypoints");
		for (JsonElement main : ep.getAsJsonArray("main")) {
			assertFalse(main.getAsString().startsWith("dev.absorbaholic.client"), "main entrypoint is common code");
		}
		boolean clientMixinScoped = false;
		for (JsonElement m : mod.getAsJsonArray("mixins")) {
			if (m.isJsonObject() && m.getAsJsonObject().get("config").getAsString().contains("client")) {
				clientMixinScoped = "client".equals(m.getAsJsonObject().get("environment").getAsString());
			}
		}
		assertTrue(clientMixinScoped, "client mixin config is declared with environment client");
	}
}
