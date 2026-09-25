package dev.absorbaholic.client.notify;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.absorbaholic.core.NotifySettings;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Client command for players without Mod Menu: {@code /absorbaholic-notify status | sound [on|off] | message [on|off]}.
 * A setting without a value is flipped. The root is not {@code /absorbaholic ...} so it cannot clash with the
 * server's command. Feedback keys: {@code absorbaholic.command.notify.sound|message}.
 */
public final class NotifyCommand {
	public static final String ROOT = "absorbaholic-notify";
	private static final String KEY = "absorbaholic.command.notify.";

	private NotifyCommand() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal(ROOT)
						.then(ClientCommands.literal("status").executes(c -> {
							NotifySettings s = NotifyConfig.get();
							feedback(c, "sound", s.sound());
							feedback(c, "message", s.message());
							return 1;
						}))
						.then(setting("sound", NotifySettings::sound, NotifySettings::withSound))
						.then(setting("message", NotifySettings::message, NotifySettings::withMessage))));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> setting(String name,
			Function<NotifySettings, Boolean> getter, BiFunction<NotifySettings, Boolean, NotifySettings> setter) {
		return ClientCommands.literal(name)
				.executes(c -> apply(c, name, getter, setter, !getter.apply(NotifyConfig.get())))
				.then(ClientCommands.literal("on").executes(c -> apply(c, name, getter, setter, true)))
				.then(ClientCommands.literal("off").executes(c -> apply(c, name, getter, setter, false)));
	}

	private static int apply(CommandContext<FabricClientCommandSource> c, String name, Function<NotifySettings, Boolean> getter,
			BiFunction<NotifySettings, Boolean, NotifySettings> setter, boolean value) {
		NotifyConfig.set(setter.apply(NotifyConfig.get(), value));
		feedback(c, name, getter.apply(NotifyConfig.get()));
		return 1;
	}

	private static void feedback(CommandContext<FabricClientCommandSource> c, String name, boolean value) {
		c.getSource().sendFeedback(Component.translatable(KEY + name, CommonComponents.optionStatus(value)));
	}
}
