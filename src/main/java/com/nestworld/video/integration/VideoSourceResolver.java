package com.nestworld.video.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves explicitly licensed, administrator-provided media sources.
 * Keys are "provider:episode_id" (e.g. "ashdi:62553").
 * Player page addresses are resolved only for the explicitly supported Ashdi
 * page format; arbitrary HTML is never passed to WaterMedia.
 */
public final class VideoSourceResolver {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Pattern FILE = Pattern.compile("(?i)(?:file|src)\\s*:\\s*['\"](https?://[^'\"]+\\.(?:m3u8|mp4)(?:\\?[^'\"]*)?)['\"]|[\"']file[\"']\\s*:\\s*[\"'](https?://[^\"']+\\.(?:m3u8|mp4)(?:\\?[^\"']*)?)[\"']");
    private static final int MAX_PAGE_BYTES = 2_000_000;
    private static final ExecutorService PAGE_RESOLVER = Executors.newFixedThreadPool(8, new DaemonThreadFactory());
    private VideoSourceResolver() {}

    public static String resolve(JsonObject episode, Map<String, String> overrides) {
        for (String field : new String[]{"m3u8", "real_url", "video_url"}) {
            String candidate = value(episode, field);
            if (AniuaApiClient.isSupportedMediaUrl(candidate)) return candidate;
        }
        String provider = value(episode, "player").toLowerCase(java.util.Locale.ROOT);
        String id = value(episode, "episode_id");
        if (id.isEmpty()) id = value(episode, "player_id");
        String configured = overrides.getOrDefault(provider + ":" + id, "");
        return AniuaApiClient.isSupportedMediaUrl(configured) ? configured : "";
    }

    /** Resolve a direct URL, an administrator mapping, or a supported Ashdi player page. */
    public static CompletableFuture<String> resolveAsync(JsonObject episode, Map<String, String> overrides) {
        String direct = resolveDirectOrOverride(episode, overrides);
        if (!direct.isBlank()) return CompletableFuture.completedFuture(direct);
        return resolvePage(value(episode, "video_url"));
    }

    /** Read the media address out of a supported (Ashdi) player page; "" when unsupported or not found. */
    public static CompletableFuture<String> resolvePage(String page) {
        if (!isAshdiPage(page)) return CompletableFuture.completedFuture("");
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(page))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("User-Agent", "NestworldVideo/1.2 (Minecraft Forge)")
                    .GET().build();
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture("");
        }
        // Page URLs are uncommon and can be returned for many episodes at once.
        // Keep their resolver work bounded so one AniUA import cannot open hundreds
        // of simultaneous HTTP connections to the provider.
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300
                        || response.body().length > MAX_PAGE_BYTES) return "";
                String html = new String(response.body(), StandardCharsets.UTF_8);
                Matcher matcher = FILE.matcher(html);
                while (matcher.find()) {
                    String candidate = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    if (AniuaApiClient.isSupportedMediaUrl(candidate)) return candidate;
                }
                return "";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return "";
            } catch (Exception ignored) { return ""; }
        }, PAGE_RESOLVER);
    }

    private static String resolveDirectOrOverride(JsonObject episode, Map<String, String> overrides) {
        for (String field : new String[]{"m3u8", "real_url", "video_url"}) {
            String candidate = value(episode, field);
            if (AniuaApiClient.isSupportedMediaUrl(candidate)) return candidate;
        }
        String provider = value(episode, "player").toLowerCase(Locale.ROOT);
        String id = value(episode, "episode_id");
        if (id.isEmpty()) id = value(episode, "player_id");
        String configured = overrides.getOrDefault(provider + ":" + id, "");
        return AniuaApiClient.isSupportedMediaUrl(configured) ? configured : "";
    }

    public static boolean isDirectMedia(String address) { return AniuaApiClient.isSupportedMediaUrl(address); }

    public static boolean isAshdiPage(String address) {
        try {
            URI uri = URI.create(address);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && (host.equals("ashdi.vip") || host.endsWith(".ashdi.vip"))
                    && uri.getPath() != null && uri.getPath().startsWith("/vod/");
        } catch (IllegalArgumentException exception) { return false; }
    }

    public static Map<String, String> load(Path serverDirectory) {
        Path path = serverDirectory.resolve("config/nestworld-video-sources.json");
        if (!Files.isRegularFile(path)) return Map.of();
        try {
            if (Files.size(path) > 256_000) throw new IOException("source mappings file too large");
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("expected JSON object");
            java.util.Map<String, String> result = new java.util.HashMap<>();
            for (var entry : parsed.getAsJsonObject().entrySet()) {
                if (!entry.getKey().matches("[a-z0-9_-]{1,40}:[A-Za-z0-9_-]{1,100}")) continue;
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) continue;
                String url = entry.getValue().getAsString();
                if (AniuaApiClient.isSupportedMediaUrl(url)) result.put(entry.getKey(), url);
            }
            return Map.copyOf(result);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load config/nestworld-video-sources.json: " + e.getMessage(), e);
        }
    }

    static String value(JsonObject json, String field) {
        JsonElement v = json.get(field);
        return v != null && v.isJsonPrimitive() && !v.isJsonNull() ? v.getAsString().trim() : "";
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int next;
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "nestworld-video-source-" + (++next));
            thread.setDaemon(true);
            return thread;
        }
    }
}
