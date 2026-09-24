package dev.absorbaholic.client.input;

import net.minecraft.client.Minecraft;

/**
 * WP-CLIENT. The absorb gesture on the client. {@link #shouldIntercept} (called by MinecraftMixin at the HEAD of
 * {@code startUseItem}) is true only when: server has the mod ({@code ClientPlayNetworking.canSend(AbsorbStartPayload.TYPE)}),
 * mode ON, {@code AbsorbRules.poseAllows(player)}, and the current target is absorbable per {@code ClientState.sources()}
 * (living entity from {@code mc.hitResult}, else {@code player.pick(blockInteractionRange, 1, true)} incl. fluids).
 * END_CLIENT_TICK: while the use key is held and intercepting, send {@code AbsorbStartPayload(target)} every tick
 * (heartbeat); on release / target loss / screen open send {@code AbsorbCancelPayload} once.
 * {@code AbsorbRules.isProtected} targets are not intercepted (vanilla use proceeds; the hint says "protected").
 */
public final class AbsorbInput {
	private AbsorbInput() {}

	public static void register() {
		// TODO(WP-CLIENT)
	}

	public static boolean shouldIntercept(Minecraft mc) {
		// TODO(WP-CLIENT)
		return false;
	}
}
