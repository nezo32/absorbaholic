package dev.absorbaholic.mixin;

import dev.absorbaholic.world.PendingWorldSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Implements {@link PendingWorldSettings} on the storage access of one world directory. Volatile: the
 * client thread writes the value (Create World), the integrated server thread reads it (SERVER_STARTING).
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin implements PendingWorldSettings {
	@Unique
	private volatile Boolean absorbaholic$pendingEnabled;

	@Override
	public void absorbaholic$setPendingEnabled(boolean enabled) {
		absorbaholic$pendingEnabled = enabled;
	}

	@Override
	public Boolean absorbaholic$takePendingEnabled() {
		Boolean value = absorbaholic$pendingEnabled;
		absorbaholic$pendingEnabled = null;
		return value;
	}
}
