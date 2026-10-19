package com.nestworld.video.command;

import com.mojang.brigadier.CommandDispatcher;
import com.nestworld.video.integration.CatalogueStore;
import com.nestworld.video.integration.FilmCatalogue;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** /films reload: read config/nestworld-films.json and publish every film that resolves into the catalogue. */
public final class FilmsCommand {
    private FilmsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("films").requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    MinecraftServer server = source.getServer();
                    List<FilmCatalogue.Film> films;
                    try { films = FilmCatalogue.load(server.getServerDirectory().toPath()); }
                    catch (Exception e) { source.sendFailure(Component.literal("Фільми: " + e.getMessage())); return 0; }
                    if (films.isEmpty()) {
                        source.sendFailure(Component.literal("Фільми: у config/nestworld-films.json немає жодного фільму"));
                        return 0;
                    }
                    source.sendSuccess(() -> Component.literal("Фільми: перевіряю джерела (" + films.size() + ")…"), false);
                    List<CompletableFuture<com.nestworld.video.Category>> jobs = films.stream().map(FilmCatalogue::build).toList();
                    CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> server.execute(() -> {
                        int ok = 0;
                        StringBuilder failed = new StringBuilder();
                        for (int i = 0; i < jobs.size(); i++) {
                            var category = jobs.get(i).exceptionally(e -> null).join();
                            if (category == null) { failed.append(failed.length() == 0 ? "" : ", ").append(films.get(i).title()); continue; }
                            try { CatalogueStore.save(server, category); ok++; }
                            catch (Exception e) { source.sendFailure(Component.literal("Каталог не збережено: " + e.getMessage())); return; }
                        }
                        int count = ok;
                        source.sendSuccess(() -> Component.literal("Фільми: додано " + count + " з " + films.size()), true);
                        if (failed.length() > 0) source.sendFailure(Component.literal("Не знайдено робочого джерела: " + failed));
                    }));
                    return 1;
                })));
    }
}
