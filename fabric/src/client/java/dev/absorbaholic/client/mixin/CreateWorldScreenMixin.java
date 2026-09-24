package dev.absorbaholic.client.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-CW. Holds the Create World "Absorbaholic Mode" value per screen instance (default ON; duck
 * {@code CreateWorldModeHolder}) and hands it to the new world's LevelStorageAccess
 * ({@code PendingWorldSettings.absorbaholic$setPendingEnabled}) with the reference's {@code @ModifyArg} on
 * {@code createNewWorld} → {@code WorldOpenFlows.createLevelFromExistingSettings} (same descriptor in 26.2 / 26.3).
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
}
