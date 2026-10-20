package com.nestworld.video.client.util;

import com.mojang.logging.LogUtils;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.relay.RelayServer;
import org.slf4j.Logger;

/**
 * A cache of the HLS stream on this computer, so a jump of a few seconds does not wait for the network: the segments
 * already played stay in memory (jumping back) and the next ones are downloaded ahead (jumping forward). It is the
 * mod's caching relay, run on 127.0.0.1 only; it serves nothing but the files of playlists this client opened itself.
 */
public final class LocalStreamCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CACHE_MB = 192, PREFETCH_SEGMENTS = 6;
    private static RelayServer relay;
    private static boolean failed;

    private LocalStreamCache() {}

    /** What the decoder should open instead of {@code url} (the url itself when it is not an HLS stream or the cache is off). */
    public static String wrap(String url) {
        if (!NestworldConfig.STREAM_CACHE.get() || url == null || !url.startsWith("http") || !url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8"))
            return url;
        RelayServer cache = start();
        return cache == null ? url : "http://127.0.0.1:" + cache.port() + cache.pathFor(url);
    }

    private static synchronized RelayServer start() {
        if (relay != null || failed) return relay;
        try {
            // Private addresses allowed: the decoder opens what a playlist lists anyway (e.g. a server relay in the LAN).
            RelayServer created = new RelayServer("127.0.0.1", 0, CACHE_MB, PREFETCH_SEGMENTS, () -> true);
            created.start();
            relay = created;
            LOGGER.info("Nestworld Video: local stream cache on 127.0.0.1:{} ({} MB, {} segments ahead)", created.port(), CACHE_MB, PREFETCH_SEGMENTS);
        } catch (Exception | LinkageError e) {
            failed = true;
            LOGGER.warn("Nestworld Video: local stream cache unavailable, streams are opened directly: {}", e.toString());
        }
        return relay;
    }
}
