package com.nestworld.video.ffmpeg;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Picks one rendition of an HLS master playlist, honouring a maximum height. */
public final class HlsVariants {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("(?<![A-Z-])BANDWIDTH=(\\d+)");

    private HlsVariants() {}

    /** The rendition to open, its height (0 = unknown or not a master playlist) and the next lower height on offer (0 = none). */
    public record Choice(String url, int height, int lowerHeight) {}

    /**
     * Returns the URL to open: the best variant not taller than {@code maxHeight} (0 = no limit), or the
     * original URL when it is not a master playlist, has separate audio renditions, or cannot be read.
     */
    public static String pick(String url, int maxHeight, String userAgent, String referer) {
        return pickChoice(url, maxHeight, 0, userAgent, referer).url();
    }

    /**
     * Like {@link #pick}, also telling which height was chosen and which lower one exists. {@code bitsPerSecond}: what
     * the connection is known to manage (0 = unknown); renditions needing more than about 80 % of it are left out
     * (the lowest one stays possible).
     */
    public static Choice pickChoice(String url, int maxHeight, long bitsPerSecond, String userAgent, String referer) {
        if (!url.toLowerCase().contains(".m3u8")) return new Choice(url, 0, 0);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent).GET();
            if (referer != null && !referer.isEmpty()) request.header("Referer", referer);
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return new Choice(url, 0, 0);
            return choose(response.body(), response.uri(), maxHeight, bitsPerSecond, url);
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.debug("Nestworld Video: could not read playlist, opening as is: {}", e.toString());
            return new Choice(url, 0, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Choice(url, 0, 0);
        }
    }

    static Choice choose(String playlist, URI base, int maxHeight, long bitsPerSecond, String fallback) {
        if (!playlist.startsWith("#EXTM3U") || !playlist.contains("#EXT-X-STREAM-INF")) return new Choice(fallback, 0, 0);
        // Audio in a separate rendition group would be lost if only the video variant were opened.
        if (playlist.contains("TYPE=AUDIO") && playlist.contains("URI=")) return new Choice(fallback, 0, 0);
        String bestUri = null;
        int bestHeight = -1; long bestBandwidth = -1;
        java.util.List<Integer> heights = new java.util.ArrayList<>();
        String lowestUri = null; int lowestHeight = Integer.MAX_VALUE; long lowestBandwidth = Long.MAX_VALUE;
        String[] lines = playlist.split("\\R");
        for (int i = 0; i < lines.length - 1; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            int next = i + 1;
            while (next < lines.length && (lines[next].isBlank() || lines[next].startsWith("#"))) next++;
            if (next >= lines.length) break;
            Matcher res = RESOLUTION.matcher(lines[i]), bw = BANDWIDTH.matcher(lines[i]);
            int height = res.find() ? Integer.parseInt(res.group(2)) : 0;
            long bandwidth = bw.find() ? Long.parseLong(bw.group(1)) : 0;
            if (height > 0) heights.add(height);
            if (bandwidth > 0 && bandwidth < lowestBandwidth) { lowestBandwidth = bandwidth; lowestHeight = height; lowestUri = lines[next].trim(); }
            if (maxHeight > 0 && height > maxHeight) continue;
            if (bitsPerSecond > 0 && bandwidth > bitsPerSecond * 0.8) continue;
            if (height > bestHeight || (height == bestHeight && bandwidth > bestBandwidth)) {
                bestHeight = height; bestBandwidth = bandwidth; bestUri = lines[next].trim();
            }
        }
        if (bestUri == null && lowestUri != null && bitsPerSecond > 0) { bestUri = lowestUri; bestHeight = lowestHeight; }   // nothing fits: the lightest one
        if (bestUri == null) return new Choice(fallback, 0, 0);
        String resolved = base.resolve(bestUri).toString();
        int lower = 0;
        for (int h : heights) if (h < bestHeight && h > lower) lower = h;
        LOGGER.info("Nestworld Video: HLS variant chosen: {}p (limit {}, connection {})", bestHeight, maxHeight == 0 ? "none" : maxHeight + "p",
                bitsPerSecond > 0 ? String.format(java.util.Locale.ROOT, "%.1f Mbit/s", bitsPerSecond / 1e6) : "not measured yet");
        return new Choice(resolved, Math.max(0, bestHeight), lower);
    }
}
