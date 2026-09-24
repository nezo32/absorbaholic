package dev.absorbaholic.client.hud;

/**
 * WP-UI. HUD elements (HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ...)): the absorb hint
 * right of the crosshair while sneaking and looking at a target in reach (source name / "???" if undiscovered, trait,
 * weakness, "needs ≤ 25 % health" for healthy mobs, "not absorbable" for blocks without a source; nothing when hints
 * are off or the mode is off), and the channel progress ring from {@code ClientState.channel()}. First person only,
 * no screen open, not F1.
 */
public final class AbsorbHud {
	private AbsorbHud() {}

	public static void register() {
		// TODO(WP-UI)
	}
}
