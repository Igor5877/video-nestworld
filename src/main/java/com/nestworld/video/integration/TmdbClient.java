package com.nestworld.video.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Film metadata from The Movie Database (titles in Ukrainian, posters, age certification). Metadata only, never video. */
public final class TmdbClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> ADULT_CERTIFICATIONS = Set.of("NC-17", "18", "18+", "R18", "X");
    private static final Set<String> CERTIFICATION_COUNTRIES = Set.of("UA", "US", "GB", "DE");
    private TmdbClient() {}

    public record Hit(long id, String title, String year, String overview) {}
    public record Details(long id, String title, String year, String poster, String overview, boolean adult) {}

    public static CompletableFuture<List<Hit>> search(String key, String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2 || q.length() > 120) return CompletableFuture.failedFuture(new IllegalArgumentException("Введи від 2 до 120 символів назви"));
        return get(key, "/search/movie?include_adult=false&language=uk-UA&query=" + URLEncoder.encode(q, StandardCharsets.UTF_8))
                .thenApply(TmdbClient::parseSearch);
    }

    public static CompletableFuture<Details> details(String key, long id) {
        return get(key, "/movie/" + id + "?language=uk-UA&append_to_response=release_dates").thenApply(TmdbClient::parseDetails);
    }

    static List<Hit> parseSearch(JsonObject root) {
        List<Hit> hits = new ArrayList<>();
        if (!(root.get("results") instanceof JsonArray results)) throw new IllegalStateException("Некоректна відповідь TMDB");
        for (JsonElement e : results) {
            if (!e.isJsonObject() || hits.size() >= 8) continue;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("id")) continue;
            hits.add(new Hit(o.get("id").getAsLong(), title(o), year(o), text(o, "overview")));
        }
        return hits;
    }

    static Details parseDetails(JsonObject o) {
        if (!o.has("id")) throw new IllegalStateException("Некоректна відповідь TMDB");
        String poster = text(o, "poster_path");
        boolean adult = o.has("adult") && o.get("adult").isJsonPrimitive() && o.get("adult").getAsBoolean();
        if (o.get("release_dates") instanceof JsonObject rd && rd.get("results") instanceof JsonArray countries)
            for (JsonElement c : countries) {
                if (!c.isJsonObject() || !CERTIFICATION_COUNTRIES.contains(text(c.getAsJsonObject(), "iso_3166_1"))) continue;
                if (c.getAsJsonObject().get("release_dates") instanceof JsonArray dates)
                    for (JsonElement d : dates)
                        if (d.isJsonObject() && ADULT_CERTIFICATIONS.contains(text(d.getAsJsonObject(), "certification").toUpperCase())) adult = true;
            }
        return new Details(o.get("id").getAsLong(), title(o), year(o),
                poster.matches("/[A-Za-z0-9._-]+") ? "https://image.tmdb.org/t/p/w342" + poster : "", text(o, "overview"), adult);
    }

    private static CompletableFuture<JsonObject> get(String key, String path) {
        if (key == null || key.isBlank()) return CompletableFuture.failedFuture(new IllegalStateException(
                "Не задано tmdb_api_key у nestworldvideo-server-content.toml (безкоштовний ключ: themoviedb.org → Settings → API)"));
        boolean bearer = key.length() > 40;
        String url = "https://api.themoviedb.org/3" + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key.trim(), StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (bearer) builder.header("Authorization", "Bearer " + key.trim());
        return HTTP.sendAsync(builder.GET().build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 401) throw new IllegalStateException("TMDB відхилив ключ (401)");
            if (response.statusCode() != 200) throw new IllegalStateException("TMDB HTTP " + response.statusCode());
            if (response.body().length() > 2_000_000) throw new IllegalStateException("Завелика відповідь TMDB");
            return JsonParser.parseString(response.body()).getAsJsonObject();
        });
    }

    private static String title(JsonObject o) {
        String t = text(o, "title");
        return t.isEmpty() ? text(o, "original_title") : t;
    }
    private static String year(JsonObject o) {
        String d = text(o, "release_date");
        return d.length() >= 4 ? d.substring(0, 4) : "";
    }
    private static String text(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString().trim() : "";
    }
}
