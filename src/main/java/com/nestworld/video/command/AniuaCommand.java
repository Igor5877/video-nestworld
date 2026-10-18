package com.nestworld.video.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.nestworld.video.integration.AniuaApiClient;
import com.nestworld.video.integration.HikkaCatalogClient;
import com.nestworld.video.integration.VideoSourceResolver;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class AniuaCommand {
    private AniuaCommand() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aniua").requires(source -> source.hasPermission(2))
                .then(Commands.argument("slug", StringArgumentType.greedyString()).executes(AniuaCommand::execute)));
    }
    private static int execute(CommandContext<CommandSourceStack> context) {
        String input = StringArgumentType.getString(context, "slug");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("AniUA: шукаю " + input + "…"), false);
        HikkaCatalogClient.resolve(input).thenCompose(slug -> {
            java.util.Map<String, String> sources = VideoSourceResolver.load(server.getServerDirectory().toPath());
            return AniuaApiClient.loadEpisodes(slug, sources);
        })
                .whenComplete((category, error) -> server.execute(() -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                source.sendFailure(Component.literal("AniUA: " + cause.getMessage()));
                return;
            }
            try {
                com.nestworld.video.integration.CatalogueStore.save(server, category);
            } catch (Exception saveError) {
                source.sendFailure(Component.literal("Каталог не збережено: " + saveError.getMessage()));
                return;
            }
            source.sendSuccess(() -> Component.translatable("command.nestworldvideo.aniua.ok", category.getName(), category.getVideos().size()), true);
        }));
        return 1;
    }
}
