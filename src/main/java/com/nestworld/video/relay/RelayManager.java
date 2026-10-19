package com.nestworld.video.relay;

import com.mojang.logging.LogUtils;
import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/** Owns the relay of this server and decides, per screen, when viewers should use it instead of the video host. */
public final class RelayManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The client replaces this with the host it joined the server through (unless public_host is configured). */
    public static final String HOST_PLACEHOLDER = "{host}";

    private static RelayServer relay;
    private record ScreenKey(ResourceKey<Level> dimension, BlockPos pos) {}
    /** Screens currently relayed, with the time (ms) since which they have been below the threshold (0 = not below). */
    private static final Map<ScreenKey, Long> ACTIVE = new HashMap<>();

    private RelayManager() {}

    public static void start() {
        stop();
        if (!RelayConfig.ENABLED.get()) return;
        try {
            relay = new RelayServer(RelayConfig.BIND_ADDRESS.get(), RelayConfig.PORT.get(), RelayConfig.CACHE_MB.get(), RelayConfig.PREFETCH_SEGMENTS.get());
            relay.start();
        } catch (Exception e) {
            LOGGER.error("Nestworld Video: could not start the relay on port {}: {}", RelayConfig.PORT.get(), e.toString());
            relay = null;
        }
    }

    public static void stop() {
        if (relay != null) relay.stop();
        relay = null;
        ACTIVE.clear();
    }

    public static boolean running() { return relay != null && relay.isRunning(); }

    /** URL the clients should open for {@code originUrl} while the screen is relayed (host placeholder unless configured). */
    public static String clientUrl(String originUrl) {
        if (!running() || originUrl == null || !originUrl.startsWith("http")) return "";
        String host = RelayConfig.PUBLIC_HOST.get().isBlank() ? HOST_PLACEHOLDER : RelayConfig.PUBLIC_HOST.get().trim();
        return "http://" + host + ":" + relay.port() + relay.pathFor(originUrl);
    }

    public static void warm(String originUrl) { if (running() && originUrl != null && originUrl.startsWith("http")) relay.warm(originUrl); }

    /**
     * Called every few seconds with the number of viewers of each screen that has any. Switches screens to the relay at
     * min_viewers (warming the cache from the second viewer on) and back after direct_after_seconds below the threshold.
     */
    public static void update(MinecraftServer server, Map<ResourceKey<Level>, Map<BlockPos, Integer>> viewersByScreen) {
        if (!running()) { if (!ACTIVE.isEmpty()) ACTIVE.clear(); return; }
        long now = System.currentTimeMillis();
        int min = RelayConfig.MIN_VIEWERS.get();
        // Screens that have viewers now.
        viewersByScreen.forEach((dimension, screens) -> screens.forEach((pos, count) -> {
            var level = server.getLevel(dimension);
            if (level == null || !level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen) || !screen.isMaster()) return;
            ScreenKey key = new ScreenKey(dimension, pos);
            if (!screen.isPlaying()) return;
            if (count >= 2 && !ACTIVE.containsKey(key)) warm(screen.getCurrentVideoUrl());
            if (count >= min) {
                if (!ACTIVE.containsKey(key)) { ACTIVE.put(key, 0L); screen.setRelay(true); LOGGER.info("Nestworld Video: screen at {} now relayed ({} viewers)", pos, count); }
                else ACTIVE.put(key, 0L);
            } else if (ACTIVE.containsKey(key)) {
                belowThreshold(key, screen, now);
            }
        }));
        // Relayed screens nobody watches any more.
        ACTIVE.keySet().removeIf(key -> {
            var count = viewersByScreen.getOrDefault(key.dimension(), Map.of()).get(key.pos());
            if (count != null) return false;
            var level = server.getLevel(key.dimension());
            var be = level == null || !level.hasChunkAt(key.pos()) ? null : level.getBlockEntity(key.pos());
            if (!(be instanceof VideoScreenBlockEntity screen)) return true;
            Long since = ACTIVE.get(key);
            if (since == null || since == 0L) { ACTIVE.put(key, now); return false; }
            if (now - since >= RelayConfig.DIRECT_AFTER_SECONDS.get() * 1000L) { screen.setRelay(false); return true; }
            return false;
        });
    }

    private static void belowThreshold(ScreenKey key, VideoScreenBlockEntity screen, long now) {
        Long since = ACTIVE.get(key);
        if (since == null || since == 0L) { ACTIVE.put(key, now); return; }
        if (now - since >= RelayConfig.DIRECT_AFTER_SECONDS.get() * 1000L) {
            ACTIVE.remove(key);
            screen.setRelay(false);
            LOGGER.info("Nestworld Video: screen at {} back to direct streaming", key.pos());
        }
    }
}
