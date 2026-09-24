package dev.absorbaholic.client.screen;

import dev.absorbaholic.client.notify.NotifyConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Client settings (opened from Mod Menu): absorb sound / absorb message ON|OFF, each saved right away, a "My traits"
 * button that is active only while in a world, and Done.
 */
public class SettingsScreen extends Screen {
	public static final String SOUND = "absorbaholic.settings.notifySound";
	public static final String MESSAGE = "absorbaholic.settings.notifyMessage";
	public static final String TRAITS = "absorbaholic.settings.traits";

	private final @Nullable Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	public SettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("absorbaholic.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(this.title, this.font);
		LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(8));
		contents.addChild(CycleButton.onOffBuilder(NotifyConfig.get().sound())
				.withTooltip(v -> Tooltip.create(Component.translatable(SOUND + ".tooltip")))
				.create(0, 0, 210, 20, Component.translatable(SOUND),
						(b, v) -> NotifyConfig.set(NotifyConfig.get().withSound(v))));
		contents.addChild(CycleButton.onOffBuilder(NotifyConfig.get().message())
				.withTooltip(v -> Tooltip.create(Component.translatable(MESSAGE + ".tooltip")))
				.create(0, 0, 210, 20, Component.translatable(MESSAGE),
						(b, v) -> NotifyConfig.set(NotifyConfig.get().withMessage(v))));
		boolean inWorld = this.minecraft.player != null;
		Button traits = contents.addChild(Button.builder(Component.translatable(TRAITS), b -> this.minecraft.gui.setScreen(TraitsScreen.own(this)))
				.width(210)
				.tooltip(Tooltip.create(Component.translatable(inWorld ? TRAITS + ".tooltip" : TRAITS + ".tooltip.no_world")))
				.build());
		traits.active = inWorld;
		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
