package dev.absorbaholic.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.world.AbsorbWorldSettings;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.server.permissions.PermissionSet;

/**
 * {@code /absorbaholic}: every subcommand, permissions (non-ops may only read settings and see their own traits),
 * clamping of {@code max}, the effects of {@code remove} / {@code reset} on the stored traits, their error replies and
 * the source suggestions. World settings are server-global and shared with parallel tests: every test that changes
 * one works synchronously and restores the previous values in {@code finally}.
 */
public class AbsorbCommandGameTests {
	private static final Identifier A = Identifier.fromNamespaceAndPath("absorbaholic_test", "cmd_a");
	private static final Identifier B = Identifier.fromNamespaceAndPath("absorbaholic_test", "cmd_b");
	private static final Identifier GONE = Identifier.fromNamespaceAndPath("absorbaholic_test", "cmd_gone");
	private static final AtomicInteger PLAYERS = new AtomicInteger();

	/**
	 * Like {@code TestSupport.survivalPlayer}, but with a unique name: player arguments take names (a UUID selector
	 * counts as "entities" for {@code EntityArgument.player()}), and every TestSupport mock player has the same name.
	 */
	private static ServerPlayer survivalPlayer(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		String name = "cmd_test_" + PLAYERS.incrementAndGet();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
		ServerPlayer p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		p.setGameMode(GameType.SURVIVAL);
		p.getInventory().clearContent();
		return p;
	}

	/** Collects everything the command sends back to its source. */
	private static final class Capture implements CommandSource {
		final List<Component> messages = new ArrayList<>();

		@Override
		public void sendSystemMessage(Component message) {
			messages.add(message);
		}

		@Override
		public boolean acceptsSuccess() {
			return true;
		}

		@Override
		public boolean acceptsFailure() {
			return true;
		}

		@Override
		public boolean shouldInformAdmins() {
			return false;
		}

		List<String> keys() {
			return messages.stream().map(AbsorbCommandGameTests::key).toList();
		}

		String last() {
			return messages.isEmpty() ? "<none>" : key(messages.getLast());
		}

		void clear() {
			messages.clear();
		}
	}

	/** Snapshot of the world settings, restored after a test. */
	private record Saved(boolean enabled, boolean keepOnDeath, boolean hints, int maxTraits) {
		static Saved of(MinecraftServer server) {
			AbsorbWorldSettings s = AbsorbWorldSettings.get(server);
			return new Saved(s.enabled(), s.keepOnDeath(), s.hints(), s.maxTraits());
		}

		void restore(MinecraftServer server) {
			AbsorbWorldSettings.setEnabled(server, enabled);
			AbsorbWorldSettings.setKeepOnDeath(server, keepOnDeath);
			AbsorbWorldSettings.setHints(server, hints);
			AbsorbWorldSettings.setMaxTraits(server, maxTraits);
		}
	}

	/** The translation key of a reply; sendFailure wraps the message in an empty red component. */
	private static String key(Component c) {
		if (c.getContents() == PlainTextContents.EMPTY && c.getSiblings().size() == 1) {
			return key(c.getSiblings().getFirst());
		}
		return c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString();
	}

	private static Object[] args(Component c) {
		return c.getContents() instanceof TranslatableContents t ? t.getArgs() : new Object[0];
	}

	private static int run(GameTestHelper h, CommandSourceStack source, String command) throws CommandSyntaxException {
		CommandDispatcher<CommandSourceStack> d = h.getLevel().getServer().getCommands().getDispatcher();
		return d.execute(command, source);
	}

	private static CommandSourceStack op(GameTestHelper h, Capture capture) {
		return h.getLevel().getServer().createCommandSourceStack().withSource(capture);
	}

	private static void assertRejected(GameTestHelper h, CommandSourceStack source, String command) {
		try {
			run(h, source, command);
			h.fail("non-op could run /" + command);
		} catch (CommandSyntaxException expected) {
			// requires() hides the node from this source
		}
	}

	private static SourceDefinition source(Identifier id) {
		return TestSupport.blockSource(id.getPath(), Identifier.withDefaultNamespace("stone"), 3, List.of(), List.of(), List.of(), List.of());
	}

	private static ServerPlayer playerWith(GameTestHelper h, TraitEntry... entries) {
		ServerPlayer p = survivalPlayer(h);
		PlayerTraits traits = PlayerTraits.EMPTY;
		for (TraitEntry e : entries) traits = traits.with(e);
		PlayerData.setTraits(p, traits);
		return p;
	}

	@GameTest
	public void modeOnOffStatus(GameTestHelper h) throws CommandSyntaxException {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Capture out = new Capture();
		CommandSourceStack op = op(h, out);
		try {
			h.assertValueEqual(run(h, op, "absorbaholic off"), 0, "off result");
			h.assertFalse(AbsorbWorldSettings.isEnabled(server), "off stored");
			h.assertValueEqual(out.last(), "absorbaholic.command.mode.off", "off reply");
			h.assertValueEqual(run(h, op, "absorbaholic status"), 0, "status off");
			h.assertValueEqual(out.last(), "absorbaholic.command.mode.status.off", "status off reply");
			h.assertValueEqual(run(h, op, "absorbaholic on"), 1, "on result");
			h.assertTrue(AbsorbWorldSettings.isEnabled(server), "on stored");
			h.assertValueEqual(out.last(), "absorbaholic.command.mode.on", "on reply");
			h.assertValueEqual(run(h, op, "absorbaholic status"), 1, "status on");
			h.assertValueEqual(out.last(), "absorbaholic.command.mode.status.on", "status on reply");
			h.assertTrue(AbsorbWorldSettings.get(server).isDirty(), "settings marked dirty for saving");
		} finally {
			saved.restore(server);
		}
		h.succeed();
	}

	@GameTest
	public void keepOnDeathAndHints(GameTestHelper h) throws CommandSyntaxException {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Capture out = new Capture();
		CommandSourceStack op = op(h, out);
		try {
			for (String setting : new String[] {"keep-on-death", "hints"}) {
				String prefix = "absorbaholic.command." + setting.replace('-', '_');
				h.assertValueEqual(run(h, op, "absorbaholic " + setting + " off"), 0, setting + " off result");
				h.assertValueEqual(out.last(), prefix + ".off", setting + " off reply");
				h.assertValueEqual(run(h, op, "absorbaholic " + setting + " status"), 0, setting + " status off");
				h.assertValueEqual(out.last(), prefix + ".status.off", setting + " status off reply");
				h.assertValueEqual(run(h, op, "absorbaholic " + setting), 0, setting + " bare = status");
				h.assertValueEqual(run(h, op, "absorbaholic " + setting + " on"), 1, setting + " on result");
				h.assertValueEqual(out.last(), prefix + ".on", setting + " on reply");
				h.assertValueEqual(run(h, op, "absorbaholic " + setting + " status"), 1, setting + " status on");
				h.assertValueEqual(out.last(), prefix + ".status.on", setting + " status on reply");
			}
			AbsorbWorldSettings s = AbsorbWorldSettings.get(server);
			run(h, op, "absorbaholic keep-on-death off");
			h.assertFalse(s.keepOnDeath(), "keep-on-death off stored");
			h.assertTrue(s.hints(), "hints untouched by keep-on-death");
			run(h, op, "absorbaholic hints off");
			h.assertFalse(s.hints(), "hints off stored");
			run(h, op, "absorbaholic keep-on-death on");
			h.assertTrue(s.keepOnDeath(), "keep-on-death on stored");
			h.assertFalse(s.hints(), "hints untouched by keep-on-death");
		} finally {
			saved.restore(server);
		}
		h.succeed();
	}

	@GameTest
	public void bareShowsEverySetting(GameTestHelper h) throws CommandSyntaxException {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Capture out = new Capture();
		try {
			AbsorbWorldSettings.setEnabled(server, true);
			AbsorbWorldSettings.setKeepOnDeath(server, false);
			AbsorbWorldSettings.setHints(server, true);
			AbsorbWorldSettings.setMaxTraits(server, 7);
			h.assertValueEqual(run(h, op(h, out), "absorbaholic"), 1, "bare result = mode");
			h.assertValueEqual(out.keys(), List.of("absorbaholic.command.status.header", "absorbaholic.command.status.mode",
					"absorbaholic.command.status.keep_on_death", "absorbaholic.command.status.hints", "absorbaholic.command.status.max"), "bare lines");
			h.assertValueEqual(key((Component) args(out.messages.get(1))[0]), "absorbaholic.command.value.on", "mode shown ON");
			h.assertValueEqual(key((Component) args(out.messages.get(2))[0]), "absorbaholic.command.value.off", "keep-on-death shown OFF");
			h.assertValueEqual(key((Component) args(out.messages.get(3))[0]), "absorbaholic.command.value.on", "hints shown ON");
			h.assertValueEqual(args(out.messages.get(4))[0], 7, "max shown");
		} finally {
			saved.restore(server);
		}
		h.succeed();
	}

	@GameTest
	public void maxSetStatusAndClamp(GameTestHelper h) throws CommandSyntaxException {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Capture out = new Capture();
		CommandSourceStack op = op(h, out);
		try {
			h.assertValueEqual(run(h, op, "absorbaholic max 5"), 5, "max 5 result");
			h.assertValueEqual(AbsorbWorldSettings.get(server).maxTraits(), 5, "max 5 stored");
			h.assertValueEqual(out.last(), "absorbaholic.command.max.set", "max set reply");
			h.assertValueEqual(run(h, op, "absorbaholic max"), 5, "max status result");
			h.assertValueEqual(out.last(), "absorbaholic.command.max.status", "max status reply");

			h.assertValueEqual(run(h, op, "absorbaholic max 0"), AbsorbCaps.MIN_MAX_TRAITS, "max 0 clamped");
			h.assertValueEqual(AbsorbWorldSettings.get(server).maxTraits(), AbsorbCaps.MIN_MAX_TRAITS, "max 0 stored clamped");
			h.assertValueEqual(out.last(), "absorbaholic.command.max.clamped", "clamp reply");
			h.assertValueEqual(args(out.messages.getLast())[3], AbsorbCaps.MIN_MAX_TRAITS, "clamp reply names the stored value");
			h.assertValueEqual(run(h, op, "absorbaholic max -3"), AbsorbCaps.MIN_MAX_TRAITS, "negative clamped");
			h.assertValueEqual(run(h, op, "absorbaholic max 1000"), AbsorbCaps.MAX_MAX_TRAITS, "max 1000 clamped");
			h.assertValueEqual(AbsorbWorldSettings.get(server).maxTraits(), AbsorbCaps.MAX_MAX_TRAITS, "max 1000 stored clamped");
			h.assertValueEqual(run(h, op, "absorbaholic max 64"), 64, "upper bound accepted");
			h.assertValueEqual(out.last(), "absorbaholic.command.max.set", "64 is not reported as clamped");
			h.assertValueEqual(run(h, op, "absorbaholic max 1"), 1, "lower bound accepted");
			try {
				run(h, op, "absorbaholic max many");
				h.fail("/absorbaholic max many parsed");
			} catch (CommandSyntaxException expected) {
				// not an integer
			}
		} finally {
			saved.restore(server);
		}
		h.succeed();
	}

	@GameTest
	public void nonOpsCanOnlyReadAndSeeOwnTraits(GameTestHelper h) throws CommandSyntaxException {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		ServerPlayer other = playerWith(h, new TraitEntry(A, 1, 1, false, false));
		String target = other.getScoreboardName();
		Capture out = new Capture();
		CommandSourceStack player = survivalPlayer(h).createCommandSourceStack().withSource(out); // a mock player is no op
		CommandSourceStack nobody = server.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS).withSource(out);
		for (CommandSourceStack source : new CommandSourceStack[] {player, nobody}) {
			for (String cmd : new String[] {"absorbaholic on", "absorbaholic off", "absorbaholic keep-on-death on",
					"absorbaholic keep-on-death off", "absorbaholic hints on", "absorbaholic hints off", "absorbaholic max 5",
					"absorbaholic traits " + target, "absorbaholic remove " + target + " " + A, "absorbaholic reset " + target}) {
				assertRejected(h, source, cmd);
			}
		}
		h.assertValueEqual(Saved.of(server), saved, "settings unchanged");
		h.assertValueEqual(PlayerData.traits(other).size(), 1, "other player's traits unchanged");

		for (String cmd : new String[] {"absorbaholic", "absorbaholic status", "absorbaholic keep-on-death",
				"absorbaholic keep-on-death status", "absorbaholic hints", "absorbaholic hints status", "absorbaholic max"}) {
			out.clear();
			run(h, player, cmd);
			h.assertFalse(out.messages.isEmpty(), "non-op read /" + cmd + " replies");
		}
		out.clear();
		h.assertValueEqual(run(h, player, "absorbaholic traits"), 0, "own traits (none)");
		h.assertValueEqual(out.keys(), List.of("absorbaholic.command.traits.none"), "own empty listing");
		h.succeed();
	}

	@GameTest
	public void traitsListingForVanillaClient(GameTestHelper h) throws CommandSyntaxException {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		List<SourceDefinition> loaded = SourceRegistry.all();
		try {
			SourceRegistry.set(List.of(source(A), source(B)));
			AbsorbWorldSettings.setEnabled(server, true);
			ServerPlayer p = playerWith(h, new TraitEntry(A, 2, 1, true, false), new TraitEntry(B, 1, 0, false, true),
					new TraitEntry(GONE, 1, 1, false, false));
			Capture out = new Capture();
			// a mock player cannot receive our payloads: vanilla client → chat listing
			h.assertValueEqual(run(h, p.createCommandSourceStack().withSource(out), "absorbaholic traits"), 3, "self result = entries");
			h.assertValueEqual(out.keys(), List.of("absorbaholic.command.traits.header", "absorbaholic.command.traits.entry",
					"absorbaholic.command.traits.entry.pure", "absorbaholic.command.traits.entry.missing"), "listing lines, oldest first");
			Object[] header = args(out.messages.get(0));
			h.assertValueEqual(header[1], 3, "header count");
			h.assertValueEqual(header[2], AbsorbWorldSettings.get(server).maxTraits(), "header max");
			Object[] first = args(out.messages.get(1));
			h.assertValueEqual(key((Component) first[1]), "absorbaholic.trait.test_trait", "trait name");
			h.assertValueEqual(key((Component) first[2]), "enchantment.level.2", "trait level");
			h.assertValueEqual(key((Component) first[3]), "absorbaholic.weakness.test_weakness", "weakness name");
			h.assertValueEqual(key((Component) first[4]), "enchantment.level.1", "weakness level");
			h.assertTrue(out.messages.get(1).getSiblings().stream().anyMatch(c -> key(c).equals("absorbaholic.command.traits.tag.mutated")),
					"mutated tag");
			h.assertTrue(out.messages.get(2).getSiblings().stream().anyMatch(c -> key(c).equals("absorbaholic.command.traits.tag.pure")),
					"pure tag");
			h.assertTrue(out.messages.get(3).getString().contains(GONE.toString()), "removed source shown by id");

			// op looks at someone else's traits, from the console
			Capture console = new Capture();
			h.assertValueEqual(run(h, op(h, console), "absorbaholic traits " + p.getScoreboardName()), 3, "op other result");
			h.assertValueEqual(console.messages.size(), 4, "op sees the same listing");

			// mode OFF: the listing says the traits are dormant
			AbsorbWorldSettings.setEnabled(server, false);
			out.clear();
			run(h, p.createCommandSourceStack().withSource(out), "absorbaholic traits");
			h.assertValueEqual(out.keys().get(1), "absorbaholic.command.traits.dormant", "dormant note when OFF");

			// the console has no traits of its own
			try {
				run(h, op(h, console), "absorbaholic traits");
				h.fail("console ran /absorbaholic traits without a player");
			} catch (CommandSyntaxException expected) {
				// a player is required
			}
		} finally {
			SourceRegistry.set(loaded);
			saved.restore(server);
		}
		h.succeed();
	}

	@GameTest
	public void traitsOfPlayerWithoutTraits(GameTestHelper h) throws CommandSyntaxException {
		ServerPlayer p = survivalPlayer(h);
		Capture out = new Capture();
		h.assertValueEqual(run(h, op(h, out), "absorbaholic traits " + p.getScoreboardName()), 0, "empty result");
		h.assertValueEqual(out.keys(), List.of("absorbaholic.command.traits.none"), "none reply");
		h.succeed();
	}

	@GameTest
	public void removeDeletesOneSource(GameTestHelper h) throws CommandSyntaxException {
		ServerPlayer p = playerWith(h, new TraitEntry(A, 2, 1, false, false), new TraitEntry(B, 1, 1, false, false),
				new TraitEntry(GONE, 1, 1, false, false));
		String name = p.getScoreboardName();
		Capture out = new Capture();
		CommandSourceStack op = op(h, out);

		PlayerData.runtime(p).dirty = false;
		h.assertValueEqual(run(h, op, "absorbaholic remove " + name + " " + A), 1, "remove result");
		h.assertValueEqual(PlayerData.traits(p).entries().stream().map(TraitEntry::source).toList(), List.of(B, GONE), "A removed, order kept");
		h.assertTrue(PlayerData.runtime(p).dirty, "engine recompute requested");
		h.assertValueEqual(out.last(), "absorbaholic.command.remove.done", "remove reply");

		// unknown source: failure, nothing changes
		PlayerTraits before = PlayerData.traits(p);
		h.assertValueEqual(run(h, op, "absorbaholic remove " + name + " " + A), 0, "unknown result");
		h.assertValueEqual(out.last(), "absorbaholic.command.remove.unknown", "unknown reply");
		h.assertValueEqual(PlayerData.traits(p), before, "unknown source changes nothing");

		// a bare path finds the player's only source with that path; a source missing from the registry can be removed
		h.assertValueEqual(run(h, op, "absorbaholic remove " + name + " " + GONE.getPath()), 1, "bare path result");
		h.assertValueEqual(PlayerData.traits(p).entries().stream().map(TraitEntry::source).toList(), List.of(B), "stale source removed");
		h.assertValueEqual(run(h, op, "absorbaholic remove " + name + " minecraft:stone"), 0, "unrelated id");
		h.assertValueEqual(out.last(), "absorbaholic.command.remove.unknown", "unrelated id reply");
		h.assertValueEqual(PlayerData.traits(p).size(), 1, "B kept");
		h.succeed();
	}

	@GameTest
	public void removeSuggestsThatPlayersSources(GameTestHelper h) {
		ServerPlayer p = playerWith(h, new TraitEntry(A, 1, 1, false, false), new TraitEntry(GONE, 1, 1, false, false));
		ServerPlayer empty = survivalPlayer(h);
		CommandSourceStack op = op(h, new Capture());
		h.assertValueEqual(suggestions(h, op, "absorbaholic remove " + p.getScoreboardName() + " "), new TreeSet<>(Set.of(A.toString(), GONE.toString())),
				"suggestions = the player's sources");
		h.assertValueEqual(suggestions(h, op, "absorbaholic remove " + p.getScoreboardName() + " absorbaholic_test:cmd_g"), new TreeSet<>(Set.of(GONE.toString())),
				"suggestions filtered by the typed prefix");
		h.assertValueEqual(suggestions(h, op, "absorbaholic remove " + empty.getScoreboardName() + " "), new TreeSet<>(), "no traits → no suggestions");
		h.assertValueEqual(suggestions(h, op, "absorbaholic remove nobody_by_this_name "), new TreeSet<>(), "unknown player → no suggestions");
		h.succeed();
	}

	private static TreeSet<String> suggestions(GameTestHelper h, CommandSourceStack source, String input) {
		CommandDispatcher<CommandSourceStack> d = h.getLevel().getServer().getCommands().getDispatcher();
		TreeSet<String> out = new TreeSet<>();
		for (Suggestion s : d.getCompletionSuggestions(d.parse(input, source)).join().getList()) out.add(s.getText());
		return out;
	}

	@GameTest
	public void resetClearsEverything(GameTestHelper h) throws CommandSyntaxException {
		ServerPlayer p = playerWith(h, new TraitEntry(A, 2, 1, false, false), new TraitEntry(B, 1, 0, true, true));
		String name = p.getScoreboardName();
		Capture out = new Capture();
		CommandSourceStack op = op(h, out);

		PlayerData.runtime(p).dirty = false;
		h.assertValueEqual(run(h, op, "absorbaholic reset " + name), 2, "reset result = removed entries");
		h.assertTrue(PlayerData.traits(p).isEmpty(), "traits cleared");
		h.assertTrue(PlayerData.runtime(p).dirty, "engine recompute requested");
		h.assertValueEqual(out.last(), "absorbaholic.command.reset.done", "reset reply");

		h.assertValueEqual(run(h, op, "absorbaholic reset " + name), 0, "reset again");
		h.assertValueEqual(out.last(), "absorbaholic.command.reset.none", "player without traits reply");
		h.succeed();
	}
}
