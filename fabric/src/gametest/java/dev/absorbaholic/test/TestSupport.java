package dev.absorbaholic.test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import dev.absorbaholic.core.SourceKind;
import dev.absorbaholic.core.Tier;
import dev.absorbaholic.registry.SourceDefinition;
import dev.absorbaholic.registry.SourceTargets;
import dev.absorbaholic.trait.AttributeEntry;
import dev.absorbaholic.trait.BehaviorEntry;
import dev.absorbaholic.world.AbsorbWorldSettings;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

/** Shared helpers for the server gametests (WP-0; packages may add helpers in their own *Support class). */
public final class TestSupport {
	private TestSupport() {}

	/**
	 * A mock server player in the test level, in survival, empty inventory, "client loaded" (a fresh mock player ignores
	 * all damage for 60 ticks otherwise). Note: its {@code doTick()} never runs by itself (no effects / air / food
	 * ticking): call {@code p.doTick()} when a test needs it.
	 */
	public static ServerPlayer survivalPlayer(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "test-mock-player"), false);
		ServerPlayer p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		p.setGameMode(GameType.SURVIVAL);
		p.getInventory().clearContent();
		return p;
	}

	/**
	 * Sets Absorbaholic Mode. It is per world (server-global) and gametests share one server, so every mode-dependent
	 * test sets it itself and does its work synchronously afterwards (tests that turn it OFF restore ON in finally).
	 */
	public static void setMode(GameTestHelper h, boolean value) {
		AbsorbWorldSettings.setEnabled(h.getLevel().getServer(), value);
	}

	/** A minimal block source for tests that must not depend on the shipped JSON. */
	public static SourceDefinition blockSource(String path, Identifier block, int maxLevel,
			List<AttributeEntry> traitAttributes, List<BehaviorEntry<?>> traitBehaviors,
			List<AttributeEntry> weaknessAttributes, List<BehaviorEntry<?>> weaknessBehaviors) {
		return new SourceDefinition(Identifier.fromNamespaceAndPath("absorbaholic_test", path),
				new SourceTargets(SourceKind.BLOCK, List.of(block), List.of()), Optional.empty(), 0xFFFFFF, Tier.COMMON, maxLevel,
				new SourceDefinition.Side("test_trait", traitAttributes, traitBehaviors),
				new SourceDefinition.Side("test_weakness", weaknessAttributes, weaknessBehaviors));
	}
}
