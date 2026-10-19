package com.nestworld.video.integration;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nestworld.video.Category;
import com.nestworld.video.Video;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads episode metadata from AniUA's currently documented v1 episode endpoint.
 * Does not reuse AniUA source code or credentials. Provider page resolution is
 * delegated to the bounded, allow-listed VideoSourceResolver.
 * An endpoint being publicly reachable does not imply permission to redistribute media.
 */
public final class AniuaApiClient {
    public static final String BASE_URL = "https://api.aniua.app";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final int MAX_EPISODES = 2000;
    private static final long CACHE_TTL_NANOS = Duration.ofMinutes(15).toNanos();
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final Gson GSON = new Gson();
    private record CachedCategory(long expiresAt, String json) {}
    private static final ConcurrentHashMap<String, CachedCategory> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<Category>> IN_FLIGHT = new ConcurrentHashMap<>();
    private AniuaApiClient() {}

    public static CompletableFuture<Category> loadEpisodes(String slug) {
        return loadEpisodes(slug, Map.of());
    }

    public static CompletableFuture<Category> loadEpisodes(String slug, Map<String, String> sources) {
        if (slug == null || !slug.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,299}"))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Некоректний slug аніме"));
        Map<String, String> safeSources = sources == null ? Map.of() : Map.copyOf(sources);
        String cacheKey = slug + "|" + safeSources.hashCode();
        long now = System.nanoTime();
        CachedCategory cached = CACHE.get(cacheKey);
        if (cached != null && cached.expiresAt() > now)
            return CompletableFuture.completedFuture(copy(cached.json()));
        CompletableFuture<Category> shared = IN_FLIGHT.computeIfAbsent(cacheKey, key ->
                loadEpisodesUncached(slug, safeSources)
                    .thenApply(category -> {
                        putCache(key, new CachedCategory(System.nanoTime() + CACHE_TTL_NANOS,
                                GSON.toJson(category)));
                        return category;
                    }));
        CompletableFuture<Category> owned = shared;
        shared.whenComplete((ignored, error) -> IN_FLIGHT.remove(cacheKey, owned));
        return shared.thenApply(category -> copy(GSON.toJson(category)));
    }

    /** Clear remote metadata after an administrator changes source mappings. */
    public static void clearCache() { CACHE.clear(); }

    private static void putCache(String key, CachedCategory value) {
        if (CACHE.size() >= MAX_CACHE_ENTRIES) {
            CACHE.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= System.nanoTime());
            if (CACHE.size() >= MAX_CACHE_ENTRIES) {
                var iterator = CACHE.keySet().iterator();
                if (iterator.hasNext()) CACHE.remove(iterator.next());
            }
        }
        CACHE.put(key, value);
    }

    private static Category copy(String json) {
        return GSON.fromJson(json, Category.class);
    }

    private static CompletableFuture<Category> loadEpisodesUncached(String slug, Map<String, String> sources) {
        // AniuaApi.ts:getEpisodes() uses v1/episodes?slug= (not the separate v2 endpoint).
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/episodes?slug=" + slug))
                .timeout(Duration.ofSeconds(25)).header("Accept", "application/json").GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
            if (response.statusCode() != 200)
                throw new IllegalStateException("AniUA API HTTP " + response.statusCode());
            if (response.body().length() > 2_000_000)
                throw new IllegalStateException("Завелика відповідь AniUA");
            return parseAsync(slug, response.body(), sources);
        });
    }

    private static CompletableFuture<Category> parseAsync(String slug, String body, Map<String, String> sources) {
        JsonElement json = JsonParser.parseString(body);
        if (!json.isJsonObject()) return CompletableFuture.failedFuture(new IllegalStateException("Некоректна відповідь AniUA"));
        JsonElement episodeElement = json.getAsJsonObject().get("episodes");
        if (episodeElement == null || !episodeElement.isJsonArray())
            return CompletableFuture.failedFuture(new IllegalStateException("AniUA: відсутній масив episodes"));
        JsonArray episodes = episodeElement.getAsJsonArray();
        if (episodes.size() > MAX_EPISODES)
            return CompletableFuture.failedFuture(new IllegalStateException("Завеликий каталог AniUA"));
        List<CompletableFuture<Video>> futures = new ArrayList<>();
        for (JsonElement element : episodes) {
            if (!element.isJsonObject()) continue;
            JsonObject episode = element.getAsJsonObject();
            futures.add(VideoSourceResolver.resolveAsync(episode, sources)
                    .thenApply(url -> makeVideo(episode, url)));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<Video> videos = futures.stream().map(CompletableFuture::join).toList();
            return new Category("AniUA: " + slug, videos);
        });
    }

    static Category parse(String slug, String body) { return parse(slug, body, Map.of()); }

    static Category parse(String slug, String body, Map<String, String> sources) {
        JsonElement json = JsonParser.parseString(body);
        if (!json.isJsonObject()) throw new IllegalStateException("Некоректна відповідь AniUA");
        JsonObject root = json.getAsJsonObject();
        JsonArray episodes = root.has("episodes") && root.get("episodes").isJsonArray()
                ? root.getAsJsonArray("episodes") : null;
        if (episodes == null) throw new IllegalStateException("AniUA: відсутній масив episodes");
        if (episodes.size() > MAX_EPISODES) throw new IllegalStateException("Завеликий каталог AniUA");
        List<Video> videos = new ArrayList<>();
        for (JsonElement element : episodes) {
            if (!element.isJsonObject()) continue;
            JsonObject episode = element.getAsJsonObject();
            // Only explicit media addresses; never pass embedded player pages to WaterMedia.
            String url = VideoSourceResolver.resolve(episode, sources);
            videos.add(makeVideo(episode, url));
        }
        return new Category("AniUA: " + slug, videos);
    }

    private static Video makeVideo(JsonObject episode, String url) {
            String number = firstString(episode, "episode");
            if (number.isBlank()) number = "?";
            String title = firstString(episode, "title_ua", "name_ua", "title_en", "name_en", "name_jp");
            String seriesTitle = title;
            if (title.isBlank()) title = "Епізод " + number;
            else title = "Епізод " + number + " — " + title;
            String team = firstString(episode, "team");
            if (!team.isBlank()) title += " — " + team;
            Video video = new Video(title, "AniUA • епізод " + number,
                    url, "", safePoster(firstString(episode, "poster")));
            video.setEpisodeInfo(number, team, seriesTitle, firstString(episode, "player"));
            return video;
        }

    /** Reject unsafe schemes, userinfo, loopback and numeric private addresses. */
    static boolean isSupportedMediaUrl(String address) {
        try {
            if (address == null || address.length() > 2048) return false;
            URI uri = URI.create(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getFragment() != null) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || host.endsWith(".test") || host.contains(":")
                    || host.matches("[0-9.]+") || host.equals("cdn.aniua.app")
                    || host.endsWith(".mooncdn.space")) return false;
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            return path.endsWith(".m3u8") || path.endsWith(".mp4");
        } catch (IllegalArgumentException exception) { return false; }
    }

    private static String safePoster(String address) {
        try {
            URI uri = URI.create(address);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? address : "";
        } catch (Exception ignored) { return ""; }
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive() && !value.isJsonNull()) {
                String s = value.getAsString();
                if (!s.isBlank()) return s;
            }
        }
        return "";
    }
}
