package dev.absorbaholic.trait;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import org.junit.jupiter.api.Test;

/** BehaviorType detects exactly the hooks a behavior class overrides. */
class HookDetectionTest {
	static final class Wipe implements Behavior<Unit> {
		@Override
		public boolean wipesOnDeath(ActiveBehavior<Unit> self) {
			return true;
		}

		@Override
		public boolean onSneakJump(ActiveBehavior<Unit> self, ServerPlayer player) {
			return false;
		}
	}

	@Test
	void detectsOverriddenHooksOnly() {
		BehaviorType<Unit> type = BehaviorType.of(Identifier.fromNamespaceAndPath("test", "wipe"), MapCodec.unit(Unit.INSTANCE), new Wipe());
		assertEquals(Set.of(Hook.WIPES_ON_DEATH, Hook.SNEAK_JUMP), type.hooks());
		BehaviorType<Unit> none = BehaviorType.of(Identifier.fromNamespaceAndPath("test", "none"), MapCodec.unit(Unit.INSTANCE), new Behavior<>() {});
		assertEquals(Set.of(), none.hooks());
	}
}
