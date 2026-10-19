package com.nestworld.video.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nestworld.video.Category;
import com.nestworld.video.content.ContentConfig;
import com.nestworld.video.integration.CatalogueStore;
import com.nestworld.video.integration.FilmCatalogue;
import com.nestworld.video.integration.TmdbClient;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /films reload                      publish config/nestworld-films.json
 * /films search <назва>              find a film in TMDB
 * /films add <tmdbId> <джерело…>     write it (Ukrainian title, poster, 18+ mark from TMDB) with the given sources and publish it
 */
public final class FilmsCommand {
    private FilmsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("films").requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    List<FilmCatalogue.Film> films;
                    try { films = FilmCatalogue.load(source.getServer().getServerDirectory().toPath()); }
                    catch (Exception e) { source.sendFailure(Component.literal("Фільми: " + e.getMessage())); return 0; }
                    if (films.isEmpty()) {
                        source.sendFailure(Component.literal("Фільми: у config/nestworld-films.json немає жодного фільму"));
                        return 0;
                    }
                    publish(source, films);
                    return 1;
                }))
                .then(Commands.literal("search").then(Commands.argument("назва", StringArgumentType.greedyString()).executes(context -> {
                    CommandSourceStack source = context.getSource();
                    MinecraftServer server = source.getServer();
                    TmdbClient.search(ContentConfig.TMDB_KEY.get(), StringArgumentType.getString(context, "назва"))
                            .whenComplete((hits, error) -> server.execute(() -> {
                                if (error != null) { source.sendFailure(Component.literal("TMDB: " + message(error))); return; }
                                if (hits.isEmpty()) { source.sendFailure(Component.literal("TMDB: нічого не знайдено")); return; }
                                source.sendSuccess(() -> Component.literal("TMDB: клацни по фільму, потім дописуй джерело").withStyle(ChatFormatting.GRAY), false);
                                for (var hit : hits)
                                    source.sendSuccess(() -> Component.literal("• " + hit.title() + (hit.year().isEmpty() ? "" : " (" + hit.year() + ")") + "  [" + hit.id() + "]")
                                            .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/films add " + hit.id() + " "))), false);
                            }));
                    return 1;
                })))
                .then(Commands.literal("add").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .then(Commands.argument("джерела", StringArgumentType.greedyString()).executes(context -> {
                            CommandSourceStack source = context.getSource();
                            MinecraftServer server = source.getServer();
                            List<String> sources = Arrays.stream(StringArgumentType.getString(context, "джерела").trim().split("\\s+")).limit(8).toList();
                            TmdbClient.details(ContentConfig.TMDB_KEY.get(), LongArgumentType.getLong(context, "id"))
                                    .whenComplete((d, error) -> server.execute(() -> {
                                        if (error != null) { source.sendFailure(Component.literal("TMDB: " + message(error))); return; }
                                        var film = new FilmCatalogue.Film(d.title(), d.year(), d.poster(), d.overview(), "", "", d.adult() ? Boolean.TRUE : null, sources);
                                        try { FilmCatalogue.append(server.getServerDirectory().toPath(), film); }
                                        catch (Exception e) { source.sendFailure(Component.literal("Фільми: " + e.getMessage())); return; }
                                        source.sendSuccess(() -> Component.literal("Фільми: «" + d.title() + "»" + (d.adult() ? " (18+)" : "") + " записано, перевіряю джерела…"), true);
                                        publish(source, List.of(film));
                                    }));
                            return 1;
                        })))));
    }

    private static String message(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private static void publish(CommandSourceStack source, List<FilmCatalogue.Film> films) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Фільми: перевіряю джерела (" + films.size() + ")…"), false);
        List<CompletableFuture<Category>> jobs = films.stream().map(FilmCatalogue::build).toList();
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
    }
}
