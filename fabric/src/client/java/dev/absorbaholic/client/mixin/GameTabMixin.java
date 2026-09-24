package dev.absorbaholic.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.absorbaholic.client.CreateWorldModeHolder;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the "Absorbaholic Mode: ON/OFF" toggle to the Game tab of the Create World screen, in the row right below
 * Difficulty (Allow Cheats moves down one row). The value lives on the screen (CreateWorldScreenMixin). The button
 * registers no uiState listener, so the Hardcore lock of Difficulty and Allow Cheats stays exactly as vanilla.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$GameTab")
public abstract class GameTabMixin {
	/** Runs right after Difficulty was added (the 3rd 2-arg addChild; same bytecode on 26.2 and 26.3). */
	@Inject(method = "<init>", require = 1, allow = 1, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;Lnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;",
			ordinal = 2, shift = At.Shift.AFTER))
	private void absorbaholic$addToggle(CreateWorldScreen screen, CallbackInfo ci, @Local GridLayout.RowHelper helper) {
		CreateWorldModeHolder holder = (CreateWorldModeHolder) screen;
		helper.addChild(CycleButton.onOffBuilder(holder.absorbaholic$isModeEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("absorbaholic.createWorld.toggle.tooltip")))
				.create(0, 0, 210, 20, Component.translatable("absorbaholic.createWorld.toggle"),
						(button, value) -> holder.absorbaholic$setModeEnabled(value)));
	}
}
