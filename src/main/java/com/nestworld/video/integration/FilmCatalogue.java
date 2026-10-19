package com.nestworld.video.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nestworld.video.Category;
import com.nestworld.video.Video;
import com.nestworld.video.integration.source.SourceChain;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Films listed by hand in config/nestworld-films.json:
 * [{"title":"...","year":"2014","poster":"https://...","description":"...","dub":"...","rating":"pg_13","adult":false,
 *   "sources":["https://ashdi.vip/vod/123","https://.../backup.m3u8"]}]
 * "sources" are tried in order, so a dead first source falls back to the next one.
 */
public final class FilmCatalogue {
    private static final int MAX_FILMS = 500, MAX_FILE_BYTES = 1_000_000;
    private FilmCatalogue() {}

    public record Film(String title, String year, String poster, String description, String dub,
                       String rating, Boolean adult, List<String> sources) {}

    public static Path file(Path serverDirectory) { return serverDirectory.resolve("config/nestworld-films.json"); }

    public static List<Film> load(Path serverDirectory) throws IOException {
        Path path = file(serverDirectory);
        if (!Files.isRegularFile(path)) return List.of();
        if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("nestworld-films.json завеликий");
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    static List<Film> parse(String text) throws IOException {
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonArray()) throw new IOException("Очікувався масив фільмів [...]");
        List<Film> films = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject() || films.size() >= MAX_FILMS) continue;
            JsonObject o = element.getAsJsonObject();
            String title = text(o, "title");
            List<String> sources = new ArrayList<>();
            if (o.get("sources") instanceof JsonArray array)
                for (JsonElement s : array)
                    if (s.isJsonPrimitive() && !s.getAsString().isBlank() && s.getAsString().length() <= 2048) sources.add(s.getAsString().trim());
            if (title.isEmpty() || sources.isEmpty()) continue;
            Boolean adult = o.has("adult") && o.get("adult").isJsonPrimitive() && o.get("adult").getAsJsonPrimitive().isBoolean()
                    ? o.get("adult").getAsBoolean() : null;
            films.add(new Film(title, text(o, "year"), safeHttps(text(o, "poster")), text(o, "description"),
                    text(o, "dub"), text(o, "rating"), adult, sources));
        }
        return films;
    }

    /** Add (or replace by title+year) one film in the file, atomically. */
    public static void append(Path serverDirectory, Film film) throws IOException {
        Path path = file(serverDirectory);
        var array = new JsonArray();
        if (Files.isRegularFile(path)) {
            JsonElement existing = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!existing.isJsonArray()) throw new IOException("nestworld-films.json: очікувався масив");
            for (JsonElement e : existing.getAsJsonArray()) {
                if (e.isJsonObject() && text(e.getAsJsonObject(), "title").equals(film.title()) && text(e.getAsJsonObject(), "year").equals(film.year())) continue;
                array.add(e);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("title", film.title()); o.addProperty("year", film.year()); o.addProperty("poster", film.poster());
        o.addProperty("description", film.description()); o.addProperty("dub", film.dub()); o.addProperty("rating", film.rating());
        if (film.adult() != null) o.addProperty("adult", film.adult());
        var sources = new JsonArray(); film.sources().forEach(sources::add); o.add("sources", sources);
        array.add(o);
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "films-", ".tmp");
        try {
            Files.writeString(temp, new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(array), StandardCharsets.UTF_8);
            try { Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    /** Resolve one film; null when none of its sources works right now. */
    public static CompletableFuture<Category> build(Film film) {
        return SourceChain.DEFAULT.resolve(film.sources()).thenApply(url -> {
            if (url.isEmpty()) return null;
            String label = film.title() + (film.year().isEmpty() ? "" : " (" + film.year() + ")");
            Video video = new Video(film.title(), film.description().isEmpty() ? "Фільм" : film.description(), url, "", film.poster());
            video.setEpisodeInfo("1", film.dub(), film.title(), "film");
            Category category = new Category("Фільм: " + label, List.of(video));
            category.setDetails(film.title(), film.poster(), "Фільм");
            category.setRating(film.rating());
            if (film.adult() != null) category.setAdult(film.adult());
            return category;
        });
    }

    private static String text(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString().trim() : "";
    }

    private static String safeHttps(String address) {
        try {
            URI uri = URI.create(address);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? address : "";
        } catch (Exception ignored) { return ""; }
    }
}
