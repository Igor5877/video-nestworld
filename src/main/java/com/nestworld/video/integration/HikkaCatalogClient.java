package com.nestworld.video.integration;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Search returns choices. Only the player decides which release to load. */
public final class HikkaCatalogClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final long SEARCH_TTL_NANOS = Duration.ofMinutes(5).toNanos();
    private static final long DETAIL_TTL_NANOS = Duration.ofMinutes(15).toNanos();
    private static final int MAX_CACHE_ENTRIES = 256;
    private record CachedJson(long expiresAt, JsonObject body) {}
    private static final ConcurrentHashMap<String, CachedJson> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<JsonObject>> IN_FLIGHT = new ConcurrentHashMap<>();
    public record Title(String slug, String title, String year, String type, String poster, String rating) {
        public Title(String slug, String title, String year, String type, String poster) { this(slug, title, year, type, poster, ""); }
        public String label() { return title + (year.isBlank() ? "" : " (" + year + ")"); }
        public String seasonLabel() {
            var matcher = java.util.regex.Pattern.compile("(?iu)(\\d+)\\s*(?:сезон|season)|(?:сезон|season)[ -]*(\\d+)").matcher(title + " " + slug);
            if (matcher.find()) return "Сезон " + (matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
            if (type.equalsIgnoreCase("movie")) return "Фільм";
            // Hikka's calendar season is NOT the series season number.
            return "Окремий реліз";
        }
    }
    private HikkaCatalogClient() {}
    public static CompletableFuture<List<Title>> searchTitles(String input) {
        String query = input == null ? "" : input.trim();
        if (query.length() < 2 || query.length() > 120) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Введи від 2 до 120 символів назви"));
        if (query.matches("[a-z0-9_-]+-[a-f0-9]{6}")) return detail(query).thenApply(List::of);
        JsonObject payload = new JsonObject(); payload.addProperty("query", query);
        var request = HttpRequest.newBuilder(URI.create("https://api.hikka.io/anime?size=50"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
        return jsonCached("search:" + normalize(query), request, SEARCH_TTL_NANOS).thenApply(root -> {
            var titles = new ArrayList<>(parseSearch(root));
            String needle = normalize(query);
            titles.sort(Comparator.comparingInt((Title t) -> {
                String haystack = normalize(t.title());
                return haystack.equals(needle) ? 0 : haystack.startsWith(needle) ? 1 : haystack.contains(needle) ? 2 : 3;
            }));
            return titles;
        });
    }
    static List<Title> parseSearch(JsonObject root) {
        if (!root.has("list") || !root.get("list").isJsonArray()) throw new IllegalStateException("Некоректна відповідь пошуку");
        List<Title> titles = new ArrayList<>();
        for (var entry : root.getAsJsonArray("list")) {
            if (!entry.isJsonObject()) continue;
            var item = parseTitle(entry.getAsJsonObject());
            if (item.slug().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,120}")) titles.add(item);
            if (titles.size() == 50) break;
        }
        return titles;
    }
    public static CompletableFuture<Title> detail(String slug) {
        if (slug == null || !slug.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,120}"))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Некоректна назва релізу"));
        return jsonCached("detail:" + slug, HttpRequest.newBuilder(URI.create("https://api.hikka.io/anime/" + slug))
                .timeout(Duration.ofSeconds(20)).GET().build(), DETAIL_TTL_NANOS).thenApply(HikkaCatalogClient::parseTitle);
    }
    public static CompletableFuture<List<Title>> seasons(String slug) {
        if (slug == null || !slug.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,120}"))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Некоректна назва релізу"));
        return jsonCached("franchise:" + slug, HttpRequest.newBuilder(URI.create("https://api.hikka.io/anime/" + slug + "/franchise?size=50"))
                .timeout(Duration.ofSeconds(20)).GET().build(), DETAIL_TTL_NANOS).thenApply(root -> {
                    var titles = new ArrayList<>(parseSearch(root));
                    titles.sort(Comparator.comparing((Title t) -> t.year().isBlank() ? "9999" : t.year()).thenComparing(Title::title));
                    return titles;
                });
    }

    /** Clear remote metadata after a manual refresh or provider change. */
    public static void clearCache() { CACHE.clear(); }

    private static CompletableFuture<JsonObject> jsonCached(String key, HttpRequest request, long ttlNanos) {
        long now = System.nanoTime();
        CachedJson cached = CACHE.get(key);
        if (cached != null && cached.expiresAt() > now) return CompletableFuture.completedFuture(cached.body().deepCopy());
        CompletableFuture<JsonObject> shared = IN_FLIGHT.computeIfAbsent(key, ignored -> json(request).thenApply(body -> {
                if (CACHE.size() >= MAX_CACHE_ENTRIES)
                    CACHE.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= System.nanoTime());
                if (CACHE.size() >= MAX_CACHE_ENTRIES) {
                    var iterator = CACHE.keySet().iterator();
                    if (iterator.hasNext()) CACHE.remove(iterator.next());
                }
                CACHE.put(key, new CachedJson(System.nanoTime() + ttlNanos, body.deepCopy()));
                return body;
            }));
        CompletableFuture<JsonObject> owned = shared;
        shared.whenComplete((ignored, error) -> IN_FLIGHT.remove(key, owned));
        return shared.thenApply(JsonObject::deepCopy);
    }
    public static CompletableFuture<String> resolve(String input) {
        return searchTitles(input).thenApply(titles -> {
            if (titles.size() == 1) return titles.get(0).slug();
            for (Title title : titles) if (title.title().equalsIgnoreCase(input.trim()) || title.slug().equalsIgnoreCase(input.trim())) return title.slug();
            throw new IllegalArgumentException(titles.isEmpty() ? "Аніме не знайдено" : "Є кілька збігів. Вибери аніме у меню екрана.");
        });
    }
    private static CompletableFuture<JsonObject> json(HttpRequest request) {
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) throw new IllegalStateException("Hikka HTTP " + response.statusCode());
            if (response.body().length() > 2_000_000) throw new IllegalStateException("Завелика відповідь Hikka");
            return JsonParser.parseString(response.body()).getAsJsonObject();
        });
    }
    private static Title parseTitle(JsonObject obj) {
        return new Title(first(obj,"slug"), first(obj,"title_ua","title_en","title_ja","slug"),
                first(obj,"year"), first(obj,"media_type"), first(obj,"image"), first(obj,"rating"));
    }
    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    private static String first(JsonObject obj, String... keys) {
        for (String key : keys) { var v = obj.get(key); if (v != null && v.isJsonPrimitive() && !v.getAsString().isBlank()) return v.getAsString(); }
        return "";
    }
}
