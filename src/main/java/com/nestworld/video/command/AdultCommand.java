package com.nestworld.video.command;

import com.mojang.brigadier.CommandDispatcher;
import com.nestworld.video.CinemaMod;
import com.nestworld.video.content.ContentConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /nestworldvideo adult everyone|ops|nobody|status: change who may watch 18+ videos for everybody at once
 * (the default of the permission nestworldvideo.adult; explicit per-player grants in a permissions mod still win).
 */
public final class AdultCommand {
    private AdultCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var adult = Commands.literal("adult").requires(source -> source.hasPermission(3))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("18+: за замовчуванням дозволено для «" + ContentConfig.ADULT_DEFAULT.get() + "»"), false);
                    return 1;
                }));
        for (String who : new String[]{"everyone", "ops", "nobody"})
            adult.then(Commands.literal(who).executes(context -> {
                ContentConfig.ADULT_DEFAULT.set(who);
                ContentConfig.SPEC.save();
                CinemaMod.refreshAdultAccess(context.getSource().getServer());
                context.getSource().sendSuccess(() -> Component.literal(switch (who) {
                    case "everyone" -> "18+ тепер доступне всім гравцям.";
                    case "nobody" -> "18+ тепер недоступне нікому (крім явно дозволених через nestworldvideo.adult).";
                    default -> "18+ тепер доступне лише операторам (та тим, кому дозволено nestworldvideo.adult).";
                }), true);
                return 1;
            }));
        dispatcher.register(Commands.literal("nestworldvideo").then(adult));
    }
}
