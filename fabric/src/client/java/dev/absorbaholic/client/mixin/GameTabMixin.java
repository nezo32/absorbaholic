package dev.absorbaholic.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * WP-CW. Adds the "Absorbaholic Mode: ON/OFF" CycleButton to the Game tab right below Difficulty: {@code <init>}
 * {@code @Inject} at the 3rd (ordinal = 2) {@code RowHelper.addChild(LayoutElement, LayoutSettings)} with
 * {@code shift = AFTER}, {@code @Local GridLayout.RowHelper} (see research/client-api.md §1; same bytecode on both versions).
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$GameTab")
public abstract class GameTabMixin {
}
