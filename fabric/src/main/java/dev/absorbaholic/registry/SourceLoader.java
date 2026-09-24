package dev.absorbaholic.registry;

/**
 * WP-REG. Reload listener for {@code data/<ns>/absorbaholic/source/<path>.json} (id {@code <ns>:<path>}):
 * prepare = read every file as JSON (FileToIdConverter.json("absorbaholic/source"), top pack wins per id) off-thread;
 * apply = {@code SourceSpecParser.parse} → {@link SourceResolver#resolve} → {@link SourceRegistry#set}. Every bad file
 * is skipped with one WARN naming the file and all its errors; {"disabled": true} files remove the id silently.
 * Logs "Loaded N Absorbaholic sources (M skipped)". Never throws.
 */
public final class SourceLoader {
	private SourceLoader() {}

	public static void register() {
		// TODO(WP-REG): ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(Absorbaholic.id("sources"), new Listener())
	}
}
