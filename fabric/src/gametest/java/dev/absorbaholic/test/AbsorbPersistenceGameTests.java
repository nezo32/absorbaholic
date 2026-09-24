package dev.absorbaholic.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.mojang.authlib.GameProfile;
import dev.absorbaholic.mixin.MinecraftServerAccessor;
import dev.absorbaholic.net.DiscoveredPayload;
import dev.absorbaholic.net.TraitsPayload;
import dev.absorbaholic.net.WorldStatePayload;
import dev.absorbaholic.player.PlayerData;
import dev.absorbaholic.player.PlayerTraits;
import dev.absorbaholic.player.TraitEntry;
import dev.absorbaholic.player.TraitsSync;
import dev.absorbaholic.trait.ActiveBehavior;
import dev.absorbaholic.trait.ActiveSet;
import dev.absorbaholic.trait.BehaviorEntry;
import dev.absorbaholic.trait.TraitEngine;
import dev.absorbaholic.trait.behavior.WipeOnDeathBehavior;
import dev.absorbaholic.world.AbsorbWorldSettings;
import dev.absorbaholic.world.PendingWorldSettings;
import dev.absorbaholic.world.WorldSettingsBootstrap;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.ChannelInfoHolder;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * WP-PLAYER: traits through death (keep-on-death on / off, mode OFF), the dragon-egg wipe (even with keep on; a dormant
 * egg never wipes), the End exit and a plain dimension change, the attachment through player save / load, per-world
 * settings (defaults, codec, on disk), the Create World pending handoff, and that sync payloads reach modded clients
 * only.
 *
 * <p>World settings are server-global and gametests share one server: every test sets what it needs, works
 * synchronously within one tick (so no engine tick or other test runs in between) and restores the previous values in
 * {@code finally}. Mock players are removed from the player list afterwards.
 */
public class AbsorbPersistenceGameTests {
	private static final Identifier OBSIDIAN = Identifier.fromNamespaceAndPath("absorbaholic_test", "obsidian");
	private static final Identifier EGG = Identifier.fromNamespaceAndPath("absorbaholic_test", "dragon_egg");
	private static final PlayerTraits TRAITS = PlayerTraits.EMPTY
			.with(new TraitEntry(OBSIDIAN, 2, 1, false, false))
			.with(new TraitEntry(EGG, 1, 1, true, false));
	private static final List<CustomPacketPayload.Type<?>> SYNC_TYPES =
			List.of(TraitsPayload.TYPE, WorldStatePayload.TYPE, DiscoveredPayload.TYPE);

	/** A survival mock player whose outbound packets can be inspected; {@code modded} = declares our S2C channels. */
	private record Mock(ServerPlayer player, EmbeddedChannel channel) {
		List<Object> drain() {
			channel.runPendingTasks();
			List<Object> out = new ArrayList<>(channel.outboundMessages());
			channel.outboundMessages().clear();
			return out;
		}
	}

	private static Mock mock(GameTestHelper h, boolean modded) {
		ServerLevel level = h.getLevel();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "persist-mock"), false);
		ServerPlayer p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		if (modded) {
			// what a modded client declares during configuration: the play listener registers these as sendable
			for (CustomPacketPayload.Type<?> type : SYNC_TYPES) {
				((ChannelInfoHolder) connection).fabric_getPendingChannelsNames(ConnectionProtocol.PLAY).add(type.id());
			}
		}
		level.getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		p.setGameMode(GameType.SURVIVAL);
		p.getInventory().clearContent();
		return new Mock(p, channel);
	}

	/** Removes a mock player from the server (if it is still the listed instance). */
	private static void leave(GameTestHelper h, ServerPlayer p) {
		MinecraftServer server = h.getLevel().getServer();
		if (server.getPlayerList().getPlayer(p.getUUID()) == p) server.getPlayerList().remove(p);
	}

	/** What the engine caches for a player whose dragon-egg weakness is active. */
	private static void activateEgg(ServerPlayer p) {
		ActiveBehavior<Unit> egg = ActiveBehavior.of(new BehaviorEntry<>(WipeOnDeathBehavior.TYPE, Unit.INSTANCE), 1, EGG, true, p.getId());
		PlayerData.runtime(p).active = new ActiveSet(List.of(egg));
	}

	/** Kills {@code p} and clicks "Respawn": the new player entity. */
	private static ServerPlayer dieAndRespawn(GameTestHelper h, ServerPlayer p) {
		p.kill(h.getLevel());
		h.assertTrue(p.isDeadOrDying(), "player died");
		return h.getLevel().getServer().getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
	}

	private static long chatWithKey(List<Object> out, String key) {
		return out.stream().filter(m -> m instanceof ClientboundSystemChatPacket p && !p.overlay()
				&& p.content().getContents() instanceof TranslatableContents tc && tc.getKey().equals(key)).count();
	}

	private static <T extends CustomPacketPayload> List<T> payloads(List<Object> out, Class<T> type) {
		return out.stream().filter(m -> m instanceof ClientboundCustomPayloadPacket p && type.isInstance(p.payload()))
				.map(m -> type.cast(((ClientboundCustomPayloadPacket) m).payload())).toList();
	}

	private static boolean anyOurPayload(List<Object> out) {
		return out.stream().anyMatch(m -> m instanceof ClientboundCustomPayloadPacket p
				&& p.payload().type().id().getNamespace().equals("absorbaholic"));
	}

	private record Saved(boolean enabled, boolean keepOnDeath) {
		static Saved of(MinecraftServer server) {
			AbsorbWorldSettings s = AbsorbWorldSettings.get(server);
			return new Saved(s.enabled(), s.keepOnDeath());
		}

		void restore(MinecraftServer server) {
			AbsorbWorldSettings.setEnabled(server, enabled);
			AbsorbWorldSettings.setKeepOnDeath(server, keepOnDeath);
		}
	}

	// ---- death ----

	@GameTest
	public void keepOnDeathOnKeepsTraits(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Mock mock = mock(h, true);
		ServerPlayer respawned = null;
		try {
			AbsorbWorldSettings.setEnabled(server, true);
			AbsorbWorldSettings.setKeepOnDeath(server, true);
			PlayerData.setTraits(mock.player(), TRAITS);
			mock.drain();
			respawned = dieAndRespawn(h, mock.player());
			List<Object> out = mock.drain();
			h.assertValueEqual(PlayerData.traits(respawned), TRAITS, "traits after respawn");
			h.assertValueEqual(chatWithKey(out, "absorbaholic.message.lost"), 0L, "no loss message");
			h.assertValueEqual(chatWithKey(out, "absorbaholic.message.wiped"), 0L, "no wipe message");
			List<TraitsPayload> sent = payloads(out, TraitsPayload.class);
			h.assertTrue(!sent.isEmpty(), "traits re-sent after respawn; outbound=" + out);
			h.assertValueEqual(sent.getLast().traits(), TRAITS, "re-sent traits");
			h.assertTrue(PlayerData.runtime(respawned).dirty, "engine rebuilds the respawned player");
		} finally {
			saved.restore(server);
			if (respawned != null) leave(h, respawned);
		}
		h.succeed();
	}

	@GameTest
	public void keepOnDeathOffLosesTraits(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Mock mock = mock(h, true);
		ServerPlayer respawned = null;
		try {
			AbsorbWorldSettings.setEnabled(server, true);
			AbsorbWorldSettings.setKeepOnDeath(server, false);
			PlayerData.setTraits(mock.player(), TRAITS);
			mock.drain();
			respawned = dieAndRespawn(h, mock.player());
			List<Object> out = mock.drain();
			h.assertTrue(PlayerData.traits(respawned).isEmpty(), "traits lost: " + PlayerData.traits(respawned));
			h.assertValueEqual(chatWithKey(out, "absorbaholic.message.lost"), 1L, "loss message; outbound=" + out);
			List<TraitsPayload> sent = payloads(out, TraitsPayload.class);
			h.assertTrue(!sent.isEmpty() && sent.getLast().traits().isEmpty(), "client told the traits are gone; outbound=" + out);
		} finally {
			saved.restore(server);
			if (respawned != null) leave(h, respawned);
		}
		h.succeed();
	}

	/** Mode OFF: traits are dormant and no world rule deletes them, not even keep-on-death off. */
	@GameTest
	public void modeOffKeepsDormantTraitsThroughDeath(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Mock mock = mock(h, false);
		ServerPlayer respawned = null;
		try {
			AbsorbWorldSettings.setEnabled(server, false);
			AbsorbWorldSettings.setKeepOnDeath(server, false);
			PlayerData.setTraits(mock.player(), TRAITS);
			respawned = dieAndRespawn(h, mock.player());
			h.assertValueEqual(PlayerData.traits(respawned), TRAITS, "dormant traits kept");
			h.assertValueEqual(chatWithKey(mock.drain(), "absorbaholic.message.lost"), 0L, "no loss message");
		} finally {
			saved.restore(server);
			if (respawned != null) leave(h, respawned);
		}
		h.succeed();
	}

	@GameTest
	public void dragonEggWipesEvenWithKeepOn(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Mock mock = mock(h, true);
		ServerPlayer respawned = null;
		try {
			AbsorbWorldSettings.setEnabled(server, true);
			AbsorbWorldSettings.setKeepOnDeath(server, true);
			PlayerData.setTraits(mock.player(), TRAITS);
			activateEgg(mock.player());
			h.assertTrue(TraitEngine.wipesOnDeath(mock.player()), "egg active");
			mock.drain();

			mock.player().kill(h.getLevel());
			// wiped at death time, before the respawn screen is even left
			h.assertTrue(PlayerData.traits(mock.player()).isEmpty(), "wiped on death: " + PlayerData.traits(mock.player()));
			List<Object> atDeath = mock.drain();
			h.assertValueEqual(chatWithKey(atDeath, "absorbaholic.message.wiped"), 1L, "wipe message; outbound=" + atDeath);
			List<TraitsPayload> sent = payloads(atDeath, TraitsPayload.class);
			h.assertTrue(!sent.isEmpty() && sent.getLast().traits().isEmpty(), "client told at death; outbound=" + atDeath);

			respawned = server.getPlayerList().respawn(mock.player(), false, Entity.RemovalReason.KILLED);
			List<Object> out = mock.drain();
			h.assertTrue(PlayerData.traits(respawned).isEmpty(), "nothing copied: " + PlayerData.traits(respawned));
			h.assertValueEqual(chatWithKey(out, "absorbaholic.message.wiped"), 0L, "wipe announced once");
			h.assertValueEqual(chatWithKey(out, "absorbaholic.message.lost"), 0L, "no loss message");
		} finally {
			saved.restore(server);
			if (respawned != null) leave(h, respawned);
		}
		h.succeed();
	}

	/** The egg is in the traits but not active (the engine's cached set is empty): no wipe. */
	@GameTest
	public void dormantEggDoesNotWipe(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Mock mock = mock(h, false);
		ServerPlayer respawned = null;
		try {
			AbsorbWorldSettings.setEnabled(server, true);
			AbsorbWorldSettings.setKeepOnDeath(server, true);
			PlayerData.setTraits(mock.player(), TRAITS);
			PlayerData.runtime(mock.player()).active = ActiveSet.EMPTY;
			respawned = dieAndRespawn(h, mock.player());
			h.assertValueEqual(PlayerData.traits(respawned), TRAITS, "dormant egg kept everything");
			h.assertValueEqual(chatWithKey(mock.drain(), "absorbaholic.message.wiped"), 0L, "no wipe message");
		} finally {
			saved.restore(server);
			if (respawned != null) leave(h, respawned);
		}
		h.succeed();
	}

	/** Leaving the End is a "respawn" of a living player: everything is kept, whatever the rules and the egg say. */
	@GameTest
	public void endExitKeepsTraits(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Mock mock = mock(h, true);
		ServerPlayer returned = null;
		try {
			AbsorbWorldSettings.setEnabled(server, true);
			AbsorbWorldSettings.setKeepOnDeath(server, false);
			PlayerData.setTraits(mock.player(), TRAITS);
			activateEgg(mock.player());
			PlayerData.runtime(mock.player()).dirty = false; // Fabric moves this runtime object over on an End exit
			mock.drain();
			returned = server.getPlayerList().respawn(mock.player(), true, Entity.RemovalReason.CHANGED_DIMENSION);
			List<Object> out = mock.drain();
			h.assertTrue(returned != mock.player(), "new player entity");
			h.assertValueEqual(PlayerData.traits(returned), TRAITS, "traits after the End exit");
			h.assertValueEqual(chatWithKey(out, "absorbaholic.message.lost") + chatWithKey(out, "absorbaholic.message.wiped"), 0L,
					"no loss / wipe message");
			h.assertTrue(PlayerData.runtime(returned).dirty, "marked dirty after Fabric's attachment transfer");
			List<TraitsPayload> sent = payloads(out, TraitsPayload.class);
			h.assertTrue(!sent.isEmpty() && sent.getLast().traits().equals(TRAITS), "traits re-sent; outbound=" + out);
		} finally {
			saved.restore(server);
			if (returned != null) leave(h, returned);
		}
		h.succeed();
	}

	@GameTest
	public void dimensionChangeKeepsTraits(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel nether = server.getLevel(Level.NETHER);
		h.assertTrue(nether != null, "the test server has a Nether");
		ServerPlayer p = mock(h, false).player();
		try {
			PlayerData.setTraits(p, TRAITS);
			ServerPlayer moved = p.teleport(new TeleportTransition(nether, new Vec3(0.5, 100, 0.5), Vec3.ZERO, 0F, 0F, TeleportTransition.DO_NOTHING));
			h.assertTrue(moved == p, "a player changes dimension without a new entity");
			h.assertTrue(p.level() == nether, "player is in the Nether");
			h.assertValueEqual(PlayerData.traits(p), TRAITS, "traits in the Nether");
			p.teleport(new TeleportTransition(h.getLevel(), h.absoluteVec(new Vec3(1, 2, 1)), Vec3.ZERO, 0F, 0F, TeleportTransition.DO_NOTHING));
			h.assertTrue(p.level() == h.getLevel(), "player is back");
			h.assertValueEqual(PlayerData.traits(p), TRAITS, "traits back home");
		} finally {
			leave(h, p);
		}
		h.succeed();
	}

	// ---- save / load ----

	@GameTest
	public void traitsSurviveSaveAndLoad(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		HolderLookup.Provider registries = level.registryAccess();
		ServerPlayer p = mock(h, false).player();
		try {
			PlayerData.setTraits(p, TRAITS);
			PlayerData.runtime(p).absorbCooldownUntil = 12345L;
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
			p.saveWithoutId(out);
			CompoundTag saved = out.buildResult();
			h.assertTrue(saved.toString().contains("absorbaholic:traits"), "traits saved with the player: " + saved);
			h.assertTrue(!saved.toString().contains("absorbaholic:runtime"), "runtime state is transient");

			ServerPlayer loaded = new ServerPlayer(level.getServer(), level, new GameProfile(p.getUUID(), "persist-load"), p.clientInformation());
			loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, saved));
			h.assertValueEqual(PlayerData.traits(loaded), TRAITS, "traits after load");
			h.assertValueEqual(PlayerData.runtime(loaded).absorbCooldownUntil, 0L, "fresh runtime after load");

			// codec alone: order, levels and flags survive
			Tag encoded = PlayerTraits.CODEC.encodeStart(NbtOps.INSTANCE, TRAITS).getOrThrow();
			h.assertValueEqual(PlayerTraits.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow(), TRAITS, "codec round trip");

			// no traits: nothing written, and a player without the attachment reads as empty
			PlayerData.setTraits(p, PlayerTraits.EMPTY);
			TagValueOutput empty = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
			p.saveWithoutId(empty);
			h.assertTrue(!empty.buildResult().toString().contains("absorbaholic:traits"), "empty traits are not saved");
			ServerPlayer fresh = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "persist-fresh"), p.clientInformation());
			fresh.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, empty.buildResult()));
			h.assertTrue(PlayerData.traits(fresh).isEmpty(), "fresh player has no traits");
		} finally {
			leave(h, p);
		}
		h.succeed();
	}

	// ---- world settings ----

	@GameTest
	public void worldSettingsDefaultsAndCodec(GameTestHelper h) {
		AbsorbWorldSettings fresh = new AbsorbWorldSettings();
		h.assertTrue(!fresh.enabled(), "a world without the Create World button starts OFF");
		h.assertTrue(fresh.keepOnDeath(), "keep-on-death defaults ON");
		h.assertTrue(fresh.hints(), "hints default ON");
		h.assertValueEqual(fresh.maxTraits(), 20, "max traits default");
		h.assertTrue(fresh.discovered().isEmpty(), "nothing discovered");

		// an old / partial settings.dat: every missing field takes its default
		AbsorbWorldSettings partial = AbsorbWorldSettings.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
		h.assertTrue(!partial.enabled() && partial.keepOnDeath() && partial.hints() && partial.maxTraits() == 20,
				"empty tag reads as defaults");

		AbsorbWorldSettings custom = custom();
		Tag encoded = AbsorbWorldSettings.CODEC.encodeStart(NbtOps.INSTANCE, custom).getOrThrow();
		AbsorbWorldSettings again = AbsorbWorldSettings.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		assertCustom(h, again, "codec round trip");
		h.succeed();
	}

	/** settings.dat through a real SavedDataStorage on disk, and an empty world folder has no settings yet. */
	@GameTest
	public void worldSettingsSaveAndLoadFromDisk(GameTestHelper h) throws Exception {
		MinecraftServer server = h.getLevel().getServer();
		Path dir = Files.createTempDirectory("absorbaholic-settings");
		try {
			SavedDataStorage empty = new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess());
			h.assertTrue(empty.get(AbsorbWorldSettings.TYPE) == null, "no settings.dat in a new folder");
			h.assertTrue(!empty.computeIfAbsent(AbsorbWorldSettings.TYPE).enabled(), "created settings start OFF");

			SavedDataStorage first = new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess());
			AbsorbWorldSettings custom = custom();
			first.set(AbsorbWorldSettings.TYPE, custom);
			custom.setDirty();
			first.saveAndJoin();
			h.assertTrue(Files.isRegularFile(dir.resolve("absorbaholic").resolve("settings.dat")), "file at data/absorbaholic/settings.dat");

			SavedDataStorage second = new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess());
			AbsorbWorldSettings loaded = second.get(AbsorbWorldSettings.TYPE);
			h.assertTrue(loaded != null, "settings read back");
			assertCustom(h, loaded, "disk round trip");
		} finally {
			try (Stream<Path> walk = Files.walk(dir)) {
				walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
		h.succeed();
	}

	private static AbsorbWorldSettings custom() {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("enabled", true);
		tag.putBoolean("keepOnDeath", false);
		tag.putBoolean("hints", false);
		tag.putInt("maxTraits", 7);
		ListTag discovered = new ListTag();
		discovered.add(StringTag.valueOf(OBSIDIAN.toString()));
		discovered.add(StringTag.valueOf(EGG.toString()));
		tag.put("discovered", discovered);
		return AbsorbWorldSettings.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
	}

	private static void assertCustom(GameTestHelper h, AbsorbWorldSettings s, String what) {
		h.assertTrue(s.enabled(), what + ": enabled");
		h.assertTrue(!s.keepOnDeath(), what + ": keepOnDeath");
		h.assertTrue(!s.hints(), what + ": hints");
		h.assertValueEqual(s.maxTraits(), 7, what + ": maxTraits");
		h.assertValueEqual(List.copyOf(s.discovered()), List.of(OBSIDIAN, EGG), what + ": discovered (in order)");
	}

	/** The Create World button value rides on the storage access and lands in settings.dat on SERVER_STARTING. */
	@GameTest
	public void pendingCreateWorldValueHandoff(GameTestHelper h) throws Exception {
		MinecraftServer server = h.getLevel().getServer();
		PendingWorldSettings access = (PendingWorldSettings) ((MinecraftServerAccessor) server).absorbaholic$getStorageSource();
		Path file = ((MinecraftServerAccessor) server).absorbaholic$getStorageSource().getLevelPath(LevelResource.DATA)
				.resolve("absorbaholic").resolve("settings.dat");
		boolean before = AbsorbWorldSettings.isEnabled(server);
		try {
			h.assertTrue(access.absorbaholic$takePendingEnabled() == null, "nothing pending on a running server");
			access.absorbaholic$setPendingEnabled(true);
			h.assertValueEqual(access.absorbaholic$takePendingEnabled(), Boolean.TRUE, "take returns the value");
			h.assertTrue(access.absorbaholic$takePendingEnabled() == null, "take clears it");

			for (boolean button : new boolean[] {false, true}) {
				AbsorbWorldSettings.setEnabled(server, !button);
				access.absorbaholic$setPendingEnabled(button);
				WorldSettingsBootstrap.onServerStarting(server);
				h.assertValueEqual(AbsorbWorldSettings.isEnabled(server), button, "button " + button + " applied");
				h.assertTrue(access.absorbaholic$takePendingEnabled() == null, "pending value consumed");
				// wait for the bootstrap's async write (a new save chains after it), then read the file itself
				AbsorbWorldSettings.get(server).setDirty();
				server.getDataStorage().saveAndJoin();
				CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
				h.assertTrue(root.get("data") instanceof CompoundTag, "settings.dat has data: " + root);
				// decode with the codec itself (it leaves fields that equal their default out of the file)
				AbsorbWorldSettings onDisk = AbsorbWorldSettings.CODEC.parse(NbtOps.INSTANCE, root.get("data")).getOrThrow();
				h.assertValueEqual(onDisk.enabled(), button, "settings.dat enabled");
			}

			// existing world (no pending value): the stored settings are authoritative
			for (boolean stored : new boolean[] {true, false}) {
				AbsorbWorldSettings.setEnabled(server, stored);
				WorldSettingsBootstrap.onServerStarting(server);
				h.assertValueEqual(AbsorbWorldSettings.isEnabled(server), stored, "existing world keeps " + stored);
			}
		} finally {
			access.absorbaholic$takePendingEnabled();
			AbsorbWorldSettings.setEnabled(server, before);
		}
		h.succeed();
	}

	// ---- sync ----

	@GameTest
	public void syncGoesToModdedPlayersOnly(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		Saved saved = Saved.of(server);
		Mock modded = mock(h, true);
		Mock vanilla = mock(h, false);
		try {
			h.assertTrue(ServerPlayNetworking.canSend(modded.player(), TraitsPayload.TYPE), "modded mock declares our channels");
			h.assertTrue(!ServerPlayNetworking.canSend(vanilla.player(), TraitsPayload.TYPE), "vanilla mock does not");

			// join: own traits, world state, full discovered set
			List<Object> join = modded.drain();
			List<TraitsPayload> joinTraits = payloads(join, TraitsPayload.class);
			h.assertTrue(joinTraits.stream().anyMatch(p -> p.owner().equals(modded.player().getUUID()) && !p.openScreen()),
					"own traits on join; outbound=" + join);
			List<WorldStatePayload> joinState = payloads(join, WorldStatePayload.class);
			h.assertTrue(!joinState.isEmpty(), "world state on join");
			h.assertValueEqual(joinState.getLast(), WorldStatePayload.of(AbsorbWorldSettings.get(server)), "join world state");
			List<DiscoveredPayload> joinDiscovered = payloads(join, DiscoveredPayload.class);
			h.assertTrue(!joinDiscovered.isEmpty() && joinDiscovered.getFirst().replace(), "full discovered set on join");
			h.assertValueEqual(joinDiscovered.getFirst().sources(), List.copyOf(AbsorbWorldSettings.get(server).discovered()),
					"join discovered set");
			h.assertTrue(!anyOurPayload(vanilla.drain()), "vanilla client got nothing on join");

			// a setting change goes to everyone modded
			AbsorbWorldSettings.setKeepOnDeath(server, !saved.keepOnDeath());
			List<WorldStatePayload> changed = payloads(modded.drain(), WorldStatePayload.class);
			h.assertTrue(changed.stream().anyMatch(p -> p.keepOnDeath() == !saved.keepOnDeath()), "state change sent: " + changed);

			// a discovery goes to everyone modded as a delta
			Identifier probe = Identifier.fromNamespaceAndPath("absorbaholic_test", "sync_probe_" + UUID.randomUUID().toString().substring(0, 8));
			AbsorbWorldSettings.discover(server, probe);
			List<DiscoveredPayload> delta = payloads(modded.drain(), DiscoveredPayload.class);
			h.assertTrue(delta.stream().anyMatch(p -> !p.replace() && p.sources().equals(List.of(probe))), "discovery delta: " + delta);

			// trait changes go to their owner
			PlayerData.setTraits(modded.player(), TRAITS);
			List<TraitsPayload> own = payloads(modded.drain(), TraitsPayload.class);
			h.assertTrue(own.size() == 1 && own.getFirst().traits().equals(TRAITS) && !own.getFirst().openScreen(), "own traits change: " + own);
			PlayerData.setTraits(vanilla.player(), TRAITS);

			// /absorbaholic traits: only a modded viewer can open the screen
			h.assertTrue(!TraitsSync.openScreen(vanilla.player(), modded.player()), "vanilla viewer has no screen");
			h.assertTrue(TraitsSync.openScreen(modded.player(), vanilla.player()), "modded viewer opens the screen");
			List<TraitsPayload> screen = payloads(modded.drain(), TraitsPayload.class);
			h.assertTrue(screen.size() == 1, "one screen payload: " + screen);
			TraitsPayload open = screen.getFirst();
			h.assertTrue(open.openScreen() && open.owner().equals(vanilla.player().getUUID()) && open.traits().equals(TRAITS)
					&& open.ownerName().equals(vanilla.player().getScoreboardName()), "screen payload of the other player: " + open);

			List<Object> vanillaOut = vanilla.drain();
			h.assertTrue(!anyOurPayload(vanillaOut), "vanilla client never got an Absorbaholic payload: " + vanillaOut);
		} finally {
			saved.restore(server);
			leave(h, modded.player());
			leave(h, vanilla.player());
		}
		h.succeed();
	}
}
