package dev.absorbaholic.client.mixin;

import dev.absorbaholic.client.CreateWorldModeHolder;
import dev.absorbaholic.world.PendingWorldSettings;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Holds the Create World "Absorbaholic Mode" value per screen instance and, when this screen creates its world,
 * hands it to that world's LevelStorageAccess, the object the integrated server is built with
 * (WorldSettingsBootstrap consumes it on SERVER_STARTING). A cancelled screen is simply dropped, so its value
 * never reaches another world.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin implements CreateWorldModeHolder {
	/** New worlds start with the mode ON; the player can switch it off on the Game tab. */
	@Unique
	private boolean absorbaholic$mode = true;

	@Override
	public boolean absorbaholic$isModeEnabled() {
		return absorbaholic$mode;
	}

	@Override
	public void absorbaholic$setModeEnabled(boolean enabled) {
		absorbaholic$mode = enabled;
	}

	@ModifyArg(method = "createNewWorld", index = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;createLevelFromExistingSettings(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/server/ReloadableServerResources;Lnet/minecraft/core/LayeredRegistryAccess;Lnet/minecraft/world/level/storage/LevelDataAndDimensions$WorldDataAndGenSettings;Ljava/util/Optional;)V"))
	private LevelStorageSource.LevelStorageAccess absorbaholic$handOffMode(LevelStorageSource.LevelStorageAccess access) {
		((PendingWorldSettings) access).absorbaholic$setPendingEnabled(absorbaholic$mode);
		return access;
	}
}
