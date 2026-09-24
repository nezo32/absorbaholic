package dev.absorbaholic.client;

/**
 * Duck interface on CreateWorldScreen (CreateWorldScreenMixin): the value of the Game tab's
 * "Absorbaholic Mode" button for this screen instance. A new screen always starts ON.
 */
public interface CreateWorldModeHolder {
	boolean absorbaholic$isModeEnabled();

	void absorbaholic$setModeEnabled(boolean enabled);
}
