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

    /**
     * Returns the URL to open: the best variant not taller than {@code maxHeight} (0 = no limit), or the
     * original URL when it is not a master playlist, has separate audio renditions, or cannot be read.
     */
    public static String pick(String url, int maxHeight, String userAgent, String referer) {
        if (!url.toLowerCase().contains(".m3u8")) return url;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", userAgent).GET();
            if (referer != null && !referer.isEmpty()) request.header("Referer", referer);
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return url;
            return choose(response.body(), response.uri(), maxHeight, url);
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.debug("Nestworld Video: could not read playlist, opening as is: {}", e.toString());
            return url;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return url;
        }
    }

    static String choose(String playlist, URI base, int maxHeight, String fallback) {
        if (!playlist.startsWith("#EXTM3U") || !playlist.contains("#EXT-X-STREAM-INF")) return fallback;
        // Audio in a separate rendition group would be lost if only the video variant were opened.
        if (playlist.contains("TYPE=AUDIO") && playlist.contains("URI=")) return fallback;
        String bestUri = null;
        int bestHeight = -1; long bestBandwidth = -1;
        String[] lines = playlist.split("\\R");
        for (int i = 0; i < lines.length - 1; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            int next = i + 1;
            while (next < lines.length && (lines[next].isBlank() || lines[next].startsWith("#"))) next++;
            if (next >= lines.length) break;
            Matcher res = RESOLUTION.matcher(lines[i]), bw = BANDWIDTH.matcher(lines[i]);
            int height = res.find() ? Integer.parseInt(res.group(2)) : 0;
            long bandwidth = bw.find() ? Long.parseLong(bw.group(1)) : 0;
            if (maxHeight > 0 && height > maxHeight) continue;
            if (height > bestHeight || (height == bestHeight && bandwidth > bestBandwidth)) {
                bestHeight = height; bestBandwidth = bandwidth; bestUri = lines[next].trim();
            }
        }
        if (bestUri == null) return fallback;
        String resolved = base.resolve(bestUri).toString();
        LOGGER.info("Nestworld Video: HLS variant chosen: {}p (limit {})", bestHeight, maxHeight == 0 ? "none" : maxHeight + "p");
        return resolved;
    }
}
