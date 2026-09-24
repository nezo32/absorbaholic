package dev.absorbaholic.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.absorbaholic.core.AbsorbCaps;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.player.TraitsSync;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceRegistry;
import dev.absorbaholic.text.Texts;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.world.AbsorbWorldSettings;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /absorbaholic}. Everyone may read the world settings (bare command, {@code status}, {@code keep-on-death},
 * {@code hints}, {@code max} without a value) and see their own traits ({@code traits}); every change and looking at
 * someone else's traits needs permission level 2 (gamemasters), like {@code /gamerule}. Changes are broadcast to ops
 * like vanilla. All feedback is built with {@link Texts#tr} so vanilla clients read English instead of raw keys.
 */
public final class AbsorbCommands {
	private static final String ROOT = "absorbaholic";

	private AbsorbCommands() {}

	/** Called from Absorbaholic#onInitialize. */
	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal(ROOT)
				.executes(c -> statusAll(c.getSource()))
				.then(Commands.literal("status").executes(c -> modeStatus(c.getSource())))
				.then(Commands.literal("on").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> setMode(c.getSource(), true)))
				.then(Commands.literal("off").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(c -> setMode(c.getSource(), false)))
				.then(Commands.literal("keep-on-death")
						.executes(c -> keepOnDeathStatus(c.getSource()))
						.then(Commands.literal("status").executes(c -> keepOnDeathStatus(c.getSource())))
						.then(Commands.literal("on").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(c -> setKeepOnDeath(c.getSource(), true)))
						.then(Commands.literal("off").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(c -> setKeepOnDeath(c.getSource(), false))))
				.then(Commands.literal("hints")
						.executes(c -> hintsStatus(c.getSource()))
						.then(Commands.literal("status").executes(c -> hintsStatus(c.getSource())))
						.then(Commands.literal("on").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(c -> setHints(c.getSource(), true)))
						.then(Commands.literal("off").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(c -> setHints(c.getSource(), false))))
				.then(Commands.literal("max")
						.executes(c -> maxStatus(c.getSource()))
						.then(Commands.argument("n", IntegerArgumentType.integer())
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(c -> setMax(c.getSource(), IntegerArgumentType.getInteger(c, "n")))))
				.then(Commands.literal("traits")
						.executes(c -> traits(c.getSource(), c.getSource().getPlayerOrException()))
						.then(Commands.argument("player", EntityArgument.player())
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(c -> traits(c.getSource(), EntityArgument.getPlayer(c, "player")))))
				.then(Commands.literal("remove").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("player", EntityArgument.player())
								.then(Commands.argument("source", IdentifierArgument.id())
										.suggests(AbsorbCommands::suggestOwnedSources)
										.executes(c -> remove(c.getSource(), EntityArgument.getPlayer(c, "player"),
												IdentifierArgument.getId(c, "source"))))))
				.then(Commands.literal("reset").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("player", EntityArgument.player())
								.executes(c -> reset(c.getSource(), EntityArgument.getPlayer(c, "player"))))));
	}

	// ---- settings ----

	private static int statusAll(CommandSourceStack source) {
		AbsorbWorldSettings s = AbsorbWorldSettings.get(source.getServer());
		source.sendSuccess(() -> Texts.tr("absorbaholic.command.status.header").withStyle(ChatFormatting.GOLD), false);
		source.sendSuccess(() -> Texts.tr("absorbaholic.command.status.mode", onOff(s.enabled())), false);
		source.sendSuccess(() -> Texts.tr("absorbaholic.command.status.keep_on_death", onOff(s.keepOnDeath())), false);
		source.sendSuccess(() -> Texts.tr("absorbaholic.command.status.hints", onOff(s.hints())), false);
		source.sendSuccess(() -> Texts.tr("absorbaholic.command.status.max", s.maxTraits()), false);
		return s.enabled() ? 1 : 0;
	}

	private static int modeStatus(CommandSourceStack source) {
		boolean on = AbsorbWorldSettings.get(source.getServer()).enabled();
		source.sendSuccess(() -> Texts.tr(on ? "absorbaholic.command.mode.status.on" : "absorbaholic.command.mode.status.off"), false);
		return on ? 1 : 0;
	}

	private static int setMode(CommandSourceStack source, boolean value) {
		AbsorbWorldSettings.setEnabled(source.getServer(), value);
		source.sendSuccess(() -> Texts.tr(value ? "absorbaholic.command.mode.on" : "absorbaholic.command.mode.off"), true);
		return value ? 1 : 0;
	}

	private static int keepOnDeathStatus(CommandSourceStack source) {
		boolean on = AbsorbWorldSettings.get(source.getServer()).keepOnDeath();
		source.sendSuccess(() -> Texts.tr(on ? "absorbaholic.command.keep_on_death.status.on"
				: "absorbaholic.command.keep_on_death.status.off"), false);
		return on ? 1 : 0;
	}

	private static int setKeepOnDeath(CommandSourceStack source, boolean value) {
		AbsorbWorldSettings.setKeepOnDeath(source.getServer(), value);
		source.sendSuccess(() -> Texts.tr(value ? "absorbaholic.command.keep_on_death.on" : "absorbaholic.command.keep_on_death.off"), true);
		return value ? 1 : 0;
	}

	private static int hintsStatus(CommandSourceStack source) {
		boolean on = AbsorbWorldSettings.get(source.getServer()).hints();
		source.sendSuccess(() -> Texts.tr(on ? "absorbaholic.command.hints.status.on" : "absorbaholic.command.hints.status.off"), false);
		return on ? 1 : 0;
	}

	private static int setHints(CommandSourceStack source, boolean value) {
		AbsorbWorldSettings.setHints(source.getServer(), value);
		source.sendSuccess(() -> Texts.tr(value ? "absorbaholic.command.hints.on" : "absorbaholic.command.hints.off"), true);
		return value ? 1 : 0;
	}

	private static int maxStatus(CommandSourceStack source) {
		int max = AbsorbWorldSettings.get(source.getServer()).maxTraits();
		source.sendSuccess(() -> Texts.tr("absorbaholic.command.max.status", max), false);
		return max;
	}

	/** Out-of-range values are clamped to [MIN_MAX_TRAITS, MAX_MAX_TRAITS] (and the reply says so). Nobody is trimmed. */
	private static int setMax(CommandSourceStack source, int requested) {
		int stored = AbsorbWorldSettings.setMaxTraits(source.getServer(), requested);
		source.sendSuccess(() -> stored == requested
				? Texts.tr("absorbaholic.command.max.set", stored)
				: Texts.tr("absorbaholic.command.max.clamped", requested, AbsorbCaps.MIN_MAX_TRAITS, AbsorbCaps.MAX_MAX_TRAITS, stored), true);
		return stored;
	}

	// ---- traits ----

	/**
	 * Opens {@code owner}'s traits screen on the executor's client when it has the mod, else lists them in chat
	 * (vanilla clients, the console, command blocks). Returns the number of entries.
	 */
	private static int traits(CommandSourceStack source, ServerPlayer owner) {
		PlayerTraits traits = PlayerData.traits(owner);
		ServerPlayer viewer = source.getPlayer();
		if (viewer != null && TraitsSync.openScreen(viewer, owner)) return traits.size();
		for (Component line : listing(source.getServer(), owner, traits)) source.sendSuccess(() -> line, false);
		return traits.size();
	}

	/** The chat listing of {@code owner}'s traits (header + one line per entry, oldest first). */
	private static List<Component> listing(MinecraftServer server, ServerPlayer owner, PlayerTraits traits) {
		List<Component> lines = new ArrayList<>();
		if (traits.isEmpty()) {
			lines.add(Texts.tr("absorbaholic.command.traits.none", owner.getDisplayName()));
			return lines;
		}
		AbsorbWorldSettings settings = AbsorbWorldSettings.get(server);
		lines.add(Texts.tr("absorbaholic.command.traits.header", owner.getDisplayName(), traits.size(), settings.maxTraits())
				.withStyle(ChatFormatting.GOLD));
		if (!settings.enabled()) lines.add(Texts.tr("absorbaholic.command.traits.dormant").withStyle(ChatFormatting.GRAY));
		for (TraitEntry entry : traits.entries()) lines.add(entryLine(entry));
		return lines;
	}

	private static Component entryLine(TraitEntry entry) {
		Optional<SourceDefinition> def = SourceRegistry.byId(entry.source());
		if (def.isEmpty()) {
			return Texts.tr("absorbaholic.command.traits.entry.missing", Component.literal(entry.source().toString()))
					.withStyle(ChatFormatting.DARK_GRAY);
		}
		SourceDefinition d = def.get();
		Component name = sourceName(d).copy().withStyle(style -> style
				.withHoverEvent(new HoverEvent.ShowText(Texts.tr(d.tier().langKey()).append("\n").append(Component.literal(d.id().toString())))));
		Component trait = described(d.traitLangKey()).withStyle(ChatFormatting.GREEN);
		MutableComponent line;
		if (entry.weaknessLevel() > 0) {
			Component weakness = described(d.weaknessLangKey()).withStyle(ChatFormatting.RED);
			line = Texts.tr("absorbaholic.command.traits.entry", name, trait, level(entry.traitLevel()), weakness, level(entry.weaknessLevel()));
		} else {
			line = Texts.tr("absorbaholic.command.traits.entry.pure", name, trait, level(entry.traitLevel()));
		}
		if (entry.mutated()) line.append(" ").append(Texts.tr("absorbaholic.command.traits.tag.mutated").withStyle(ChatFormatting.LIGHT_PURPLE));
		if (entry.pure()) line.append(" ").append(Texts.tr("absorbaholic.command.traits.tag.pure").withStyle(ChatFormatting.AQUA));
		return line;
	}

	/** A trait / weakness name whose hover shows its description. */
	private static MutableComponent described(String key) {
		return Texts.tr(key).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Texts.tr(key + ".desc"))));
	}

	/**
	 * Display name of a source: its own {@code absorbaholic.source.<path>} name when our lang has one (tag-based
	 * sources), else the name of its first direct block / entity target, else its id.
	 */
	private static Component sourceName(SourceDefinition def) {
		String key = "absorbaholic.source." + def.id().getPath();
		if (Texts.englishOf(key) != null) return Texts.tr(key);
		if (!def.targets().ids().isEmpty()) {
			Identifier first = def.targets().ids().getFirst();
			Optional<Component> name = switch (def.kind()) {
				case BLOCK -> BuiltInRegistries.BLOCK.getOptional(first).map(b -> (Component) b.getName());
				case ENTITY -> BuiltInRegistries.ENTITY_TYPE.getOptional(first).map(t -> t.getDescription());
			};
			if (name.isPresent()) return name.get();
		}
		return Component.literal(def.id().toString());
	}

	private static Component level(int level) {
		return Component.translatableWithFallback("enchantment.level." + level, String.valueOf(level));
	}

	private static Component onOff(boolean on) {
		return on ? Texts.tr("absorbaholic.command.value.on").withStyle(ChatFormatting.GREEN)
				: Texts.tr("absorbaholic.command.value.off").withStyle(ChatFormatting.RED);
	}

	// ---- remove / reset ----

	private static CompletableFuture<Suggestions> suggestOwnedSources(CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
		ServerPlayer player;
		try {
			player = EntityArgument.getPlayer(c, "player");
		} catch (CommandSyntaxException | IllegalArgumentException e) {
			return builder.buildFuture();
		}
		return SharedSuggestionProvider.suggestResource(PlayerData.traits(player).entries().stream().map(TraitEntry::source), builder);
	}

	private static int remove(CommandSourceStack source, ServerPlayer player, Identifier requested) {
		PlayerTraits traits = PlayerData.traits(player);
		Optional<TraitEntry> entry = find(traits, requested);
		if (entry.isEmpty()) {
			source.sendFailure(Texts.tr("absorbaholic.command.remove.unknown", player.getDisplayName(), Component.literal(requested.toString())));
			return 0;
		}
		Identifier id = entry.get().source();
		PlayerData.setTraits(player, traits.without(id));
		TraitEngine.markDirty(player);
		Component name = SourceRegistry.byId(id).map(AbsorbCommands::sourceName).orElseGet(() -> Component.literal(id.toString()));
		source.sendSuccess(() -> Texts.tr("absorbaholic.command.remove.done", name, player.getDisplayName()), true);
		return 1;
	}

	/**
	 * The entry for {@code requested}; a bare path (parsed as {@code minecraft:<path>}) also finds the player's only
	 * source with that path in another namespace, so {@code remove Steve coal} works for {@code absorbaholic:coal}.
	 */
	private static Optional<TraitEntry> find(PlayerTraits traits, Identifier requested) {
		Optional<TraitEntry> exact = traits.get(requested);
		if (exact.isPresent() || !requested.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) return exact;
		List<TraitEntry> byPath = traits.entries().stream().filter(e -> e.source().getPath().equals(requested.getPath())).toList();
		return byPath.size() == 1 ? Optional.of(byPath.getFirst()) : Optional.empty();
	}

	private static int reset(CommandSourceStack source, ServerPlayer player) {
		int count = PlayerData.traits(player).size();
		if (count == 0) {
			source.sendFailure(Texts.tr("absorbaholic.command.reset.none", player.getDisplayName()));
			return 0;
		}
		PlayerData.setTraits(player, PlayerTraits.EMPTY);
		TraitEngine.markDirty(player);
		source.sendSuccess(() -> Texts.tr("absorbaholic.command.reset.done", player.getDisplayName(), count), true);
		return count;
	}
}
