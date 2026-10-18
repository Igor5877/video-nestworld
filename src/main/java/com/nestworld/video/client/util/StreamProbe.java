package com.nestworld.video.client.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot HTTP check of a stream that VLC failed to open. VLC's own error only reaches the launcher
 * console, so this reproduces the request from Java and writes the outcome to latest.log. The query
 * string is never logged because stream URLs often carry access tokens.
 */
final class StreamProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final int MAX_BYTES = 262_144;

    private StreamProbe() {}

    static void probeAsync(String url) {
        Thread thread = new Thread(() -> probe(url), "nestworld-video-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private static String safe(String address) {
        int query = address.indexOf('?');
        return query < 0 ? address : address.substring(0, query) + "?<redacted>";
    }

    private static void probe(String url) {
        String shown = safe(url);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                    .header("Range", "bytes=0-" + (MAX_BYTES - 1))
                    .GET().build();
            HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var in = response.body()) { body = in.readNBytes(MAX_BYTES); }
            String text = new String(body, StandardCharsets.UTF_8);
            String type = response.headers().firstValue("content-type").orElse("?");
            boolean playlist = text.startsWith("#EXTM3U");
            StringBuilder detail = new StringBuilder();
            if (playlist) {
                int variants = 0, bestWidth = 0, bestHeight = 0;
                Matcher matcher = RESOLUTION.matcher(text);
                while (matcher.find()) {
                    variants++;
                    int width = Integer.parseInt(matcher.group(1)), height = Integer.parseInt(matcher.group(2));
                    if (height > bestHeight) { bestWidth = width; bestHeight = height; }
                }
                detail.append(variants > 0
                        ? ", master playlist with " + variants + " variant(s), best " + bestWidth + "x" + bestHeight
                        : ", media playlist (single quality)");
            }
            LOGGER.info("Nestworld Video probe: {} -> HTTP {} via {}, content-type={}, hls={}{}",
                    shown, response.statusCode(), safe(response.uri().toString()), type, playlist, detail);
            if (response.statusCode() >= 400)
                LOGGER.warn("Nestworld Video probe: the server refused the request (HTTP {}). It may be blocked "
                        + "for this region/network or require different headers.", response.statusCode());
            else if (!playlist && type.toLowerCase().contains("html"))
                LOGGER.warn("Nestworld Video probe: got an HTML page instead of a video (blocked or redirected).");
        } catch (IOException e) {
            LOGGER.warn("Nestworld Video probe: {} could not be reached: {}: {}. The host is probably blocked "
                    + "or unreachable from this network.", shown, e.getClass().getSimpleName(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOGGER.warn("Nestworld Video probe failed for {}: {}", shown, e.toString());
        }
    }
}
