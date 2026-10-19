package com.nestworld.video.client;

import com.nestworld.video.client.util.Display;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketScreenWatch;
import com.nestworld.video.network.PacketVideoEnded;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientVideoManager {

    private static final ClientVideoManager INSTANCE = new ClientVideoManager();
    private final Map<BlockPos, Display> activeDisplays = new ConcurrentHashMap<>();
    /** Screens that the server says should be playing, including displays outside the distance limit. */
    private final Map<BlockPos, String> desiredScreens = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> lastViewerReports = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> lastHistoryRecords = new ConcurrentHashMap<>();
    /** Screens for which an end-of-video report was already sent for the url they are playing. */
    /** Per screen: when the decoder was last found out of step, and when it was last pulled back. */
    private final Map<BlockPos, long[]> driftState = new ConcurrentHashMap<>();
    /** Screens whose relay did not work for this client: they read the video host directly until the given time (ms). */
    private final Map<BlockPos, Long> relayFailedUntil = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> relayStartedNanos = new ConcurrentHashMap<>();
    private final Map<BlockPos, String> lastEndReports = new ConcurrentHashMap<>();
    /** Announcement of the next episode, shown a few seconds before the current one ends. */
    public record NextUp(BlockPos pos, int seconds, String title) {}
    public static final long NEXT_UP_MS = 5_000;
    private volatile NextUp nextUp;
    public NextUp getNextUp() { return nextUp; }
    public NextUp getNextUp(BlockPos pos) { var n = nextUp; return n != null && n.pos().equals(pos) ? n : null; }

    private ClientVideoManager() {}

    public static ClientVideoManager getInstance() {
        return INSTANCE;
    }

    public void startPlaying(BlockPos pos, String url) {
        // Stop any existing video at this position first
        stopPlaying(pos);

        if (url == null || url.isEmpty()) {
            return;
        }

        try {
            String source = sourceFor(pos, url);
            if (!source.equals(url)) {
                com.mojang.logging.LogUtils.getLogger().info("Nestworld Video: reading the stream of the screen at {} through the server relay", pos);
                relayStartedNanos.put(pos, System.nanoTime());
            } else relayStartedNanos.remove(pos);
            Display display = Display.create(url, source);
            display.syncPosition(screenPosition(pos));
            activeDisplays.put(pos, display);
        } catch (RuntimeException exception) {
            com.mojang.logging.LogUtils.getLogger().warn("Unable to start video at {}", pos, exception);
        }
    }

    public void stopPlaying(BlockPos pos) {
        reportViewer(pos, false);
        lastEndReports.remove(pos);
        driftState.remove(pos);
        Display display = activeDisplays.remove(pos);
        if (display != null) {
            display.release();
        }
    }
    
    public void setPlaying(BlockPos pos, boolean playing) {
        Display display = activeDisplays.get(pos);
        if (display != null) {
            display.setPlaying(playing);
        }
    }

    /** Reopen every active stream (e.g. after a quality change); the tick restarts playing screens in sync. */
    public void restartAll() {
        for (BlockPos pos : activeDisplays.keySet()) stopPlaying(pos);
    }

    public Display getDisplay(BlockPos pos) {
        return activeDisplays.get(pos);
    }

    public long getPlaybackTime(BlockPos pos) {
        Display display = activeDisplays.get(pos);
        return display == null ? -1 : display.getPlaybackTime();
    }

    /** Record a loaded screen without creating a decoder from the render callback. */
    public void observe(VideoScreenBlockEntity screen) {
        BlockPos pos = screen.getBlockPos();
        if (!screen.isMaster() || screen.isStopped() || screen.getCurrentVideoUrl().isBlank())
            desiredScreens.remove(pos);
        else
            desiredScreens.put(pos, screen.getCurrentVideoUrl());
    }

    public void syncPosition(BlockPos pos, long positionMs) {
        Display display = activeDisplays.get(pos);
        if (display != null) display.syncPosition(positionMs);
    }

    private void reportViewer(BlockPos pos, boolean watching) {
        if (watching) {
            if (Minecraft.getInstance().getConnection() != null)
                Networking.INSTANCE.sendToServer(new PacketScreenWatch(pos, true));
            lastViewerReports.put(pos, 0L);
        } else if (lastViewerReports.remove(pos) != null) {
            if (Minecraft.getInstance().getConnection() != null)
                Networking.INSTANCE.sendToServer(new PacketScreenWatch(pos, false));
        }
    }

    /** What to actually open for the video of the screen at {@code pos}: the server's relay when it offers one, else the video's own URL. */
    private String sourceFor(BlockPos pos, String url) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)) return url;
        String relay = screen.getRelayUrl();
        Long failedUntil = relayFailedUntil.get(pos);
        if (relay == null || relay.isBlank() || (failedUntil != null && System.currentTimeMillis() < failedUntil)) return url;
        return relay.replace(com.nestworld.video.relay.RelayManager.HOST_PLACEHOLDER, serverHost());
    }

    /** Host the player joined the server through (the relay listens on the same machine unless public_host says otherwise). */
    private static String serverHost() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null && connection.getConnection().getRemoteAddress() instanceof java.net.InetSocketAddress address) {
            String host = address.getHostString();
            return host.contains(":") ? "[" + host + "]" : host;
        }
        return "127.0.0.1";
    }

    private long screenPosition(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)
            return screen.getPlaybackPositionMs();
        return 0;
    }

    public void reconcile(VideoScreenBlockEntity screen) {
        BlockPos pos = screen.getBlockPos();
        Display existing = activeDisplays.get(pos);
        if (!screen.isMaster() || screen.isStopped() || screen.getCurrentVideoUrl().isBlank()) {
            desiredScreens.remove(pos);
            if (existing != null) stopPlaying(pos);
            return;
        }
        if (screen.isAdult() && !ClientData.isAdultAllowed()) {
            // 18+ video and this player may not watch it: nothing is decoded or played, the screen shows a placeholder.
            desiredScreens.remove(pos);
            if (existing != null) stopPlaying(pos);
            return;
        }
        desiredScreens.put(pos, screen.getCurrentVideoUrl());
        if (existing != null && (!existing.getUrl().equals(screen.getCurrentVideoUrl())
                || !existing.getSourceUrl().equals(sourceFor(pos, screen.getCurrentVideoUrl())))) {
            stopPlaying(pos); existing = null;      // another video, or the server switched the screen to/from its relay
        }
        if (existing == null && screen.isPlaying() && streamSlotAvailable(screen)) {
            startPlaying(pos, screen.getCurrentVideoUrl());
            existing = activeDisplays.get(pos);
        }
        if (existing != null) existing.setPlaying(screen.isPlaying());
    }

    private boolean streamSlotAvailable(VideoScreenBlockEntity screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        int maxDistance = NestworldConfig.MAX_STREAM_DISTANCE.get();
        if (mc.player.distanceToSqr(screen.getBlockPos().getX() + .5,
                screen.getBlockPos().getY() + .5, screen.getBlockPos().getZ() + .5)
                > (double) maxDistance * maxDistance) return false;
        return activeDisplays.size() < NestworldConfig.MAX_ACTIVE_STREAMS.get();
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        double px = mc.player.getX();
        double py = mc.player.getEyeY();
        double pz = mc.player.getZ();

        // Retry screens that were outside the distance/stream budget. This runs on
        // the client tick instead of the block-entity render callback, so opening a
        // WaterMedia decoder cannot stall the render thread.
        for (Map.Entry<BlockPos, String> wanted : desiredScreens.entrySet()) {
            BlockPos pos = wanted.getKey();
            if (!mc.level.hasChunkAt(pos)) continue;
            if (!(mc.level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)
                    || !screen.isMaster() || screen.isStopped()
                    || !wanted.getValue().equals(screen.getCurrentVideoUrl())) {
                desiredScreens.remove(pos, wanted.getValue());
                continue;
            }
            reconcile(screen);
        }

        boolean watching = false;
        NextUp announce = null;
        for (Map.Entry<BlockPos, Display> entry : activeDisplays.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!mc.level.hasChunkAt(pos) || !(mc.level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)) {
                reportViewer(pos, false);
                stopPlaying(pos); continue;
            }
            int maxDistance = NestworldConfig.MAX_STREAM_DISTANCE.get();
            if (mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5)
                    > (double) maxDistance * maxDistance) {
                reportViewer(pos, false);
                stopPlaying(pos);
                continue;
            }
            reconcile(screen);
            if (!activeDisplays.containsKey(pos)) continue;
            Display current = activeDisplays.get(pos);
            // The relay is an optimisation: if it cannot be reached (or is too slow to start), fall back to the video host.
            if (current != null && !current.getSourceUrl().equals(current.getUrl())) {
                Long startedAt = relayStartedNanos.get(pos);
                boolean slow = startedAt != null && current.isLoading() && System.nanoTime() - startedAt > 25_000_000_000L;
                if (current.isBroken() || slow) {
                    com.mojang.logging.LogUtils.getLogger().warn("Nestworld Video: the server relay did not work for the screen at {}; using the video host directly", pos);
                    relayFailedUntil.put(pos, System.currentTimeMillis() + 300_000L);
                    stopPlaying(pos);
                    continue;
                }
            }
            if (current != null) current.tick();
            // The server's timeline ran past the end of the stream (e.g. the decoder only just started or lagged): report it
            // anyway, otherwise nobody tells the server and auto-next never happens.
            if (current != null && current.getDuration() > 0 && screen.getPlaybackPositionMs() < current.getDuration() - 1000)
                lastEndReports.remove(pos);
            if (current != null && screen.isPlaying() && current.getDuration() > 0
                    && screen.getPlaybackPositionMs() > current.getDuration() + 3000 && current.getPlaybackTime() >= 0
                    && lastEndReports.putIfAbsent(pos, current.getUrl()) == null) {
                recordHistory(pos, current, true);
                Networking.INSTANCE.sendToServer(new PacketVideoEnded(pos, current.getUrl()));
            }
            if (current != null && screen.isPlaying() && current.pollEnded()) {
                recordHistory(pos, current, true);
                Networking.INSTANCE.sendToServer(new PacketVideoEnded(pos, current.getUrl()));
            }
            if (current != null)
                screen.setClientPlaybackAdvancing(current.isPlaying() && !current.isBuffering());
            if (current != null && current.needsInitialSync())
                current.syncPosition(screen.getPlaybackPositionMs());
            long now = mc.level.getGameTime();
            if (current != null && screen.isPlaying() && !current.isBuffering()) {
                Long lastRecord = lastHistoryRecords.get(pos);
                if (lastRecord == null || now - lastRecord >= 100) { lastHistoryRecords.put(pos, now); recordHistory(pos, current, false); }
                long duration = current.getDuration(), time = current.getPlaybackTime();
                if (announce == null && duration > 0 && time >= 0 && duration - time <= NEXT_UP_MS && duration - time > 0
                        && screen.isAutoNext() && !screen.isRepeat() && !screen.isNextCancelled()
                        && mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) < 64 * 64) {
                    var next = com.nestworld.video.NextEpisode.find(categories(), current.getUrl());
                    if (next != null) announce = new NextUp(pos, (int) Math.ceil((duration - time) / 1000.0), next.getTitle());
                }
            }
            if (current != null) correctDrift(pos, screen, current, mc.level.getGameTime());
            Long lastReport = lastViewerReports.get(pos);
            if (lastReport == null || now - lastReport >= 100) {
                reportViewer(pos, true);
                lastViewerReports.put(pos, now);
            }
            // Distance to the screen surface, so the bottom of a 100-block-tall wall is audible.
            var bounds = screen.getRenderBoundingBox();
            double dx = Math.max(bounds.minX - px, Math.max(0, px - bounds.maxX));
            double dy = Math.max(bounds.minY - py, Math.max(0, py - bounds.maxY));
            double dz = Math.max(bounds.minZ - pz, Math.max(0, pz - bounds.maxZ));
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            current = activeDisplays.get(pos);
            // Is this screen playing close to the player and roughly in front of them? Then tearing would be visible.
            if (current != null && current.isPlaying() && dist < 48) {
                var look = mc.player.getViewVector(1.0F);
                double cx = (bounds.minX + bounds.maxX) / 2 - px, cy = (bounds.minY + bounds.maxY) / 2 - py, cz = (bounds.minZ + bounds.maxZ) / 2 - pz;
                double length = Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (length < 1e-3 || (look.x * cx + look.y * cy + look.z * cz) / length > 0.3) watching = true;
            }
            if (current != null) {
                double diagonal = Math.hypot(screen.getScreenCols(), screen.getScreenRows());
                current.applySpatialVolume(com.nestworld.video.client.util.SoundFalloff.factor(dist, diagonal,
                        NestworldConfig.MAX_STREAM_DISTANCE.get()));
                // Speakers sit at the screen's left/right edges; stay within a few blocks of the centre for wide walls.
                var right = screen.getBlockState().getValue(com.nestworld.video.block.VideoScreenBlock.FACING).getCounterClockWise();
                double half = Math.min(screen.getScreenCols() / 2.0, 6.0) * 0.85;
                // Standing close, speakers at the very edges would hard-pan the sound: keep the angle gentle.
                double toCentre = Math.sqrt(Math.pow((bounds.minX + bounds.maxX) / 2 - px, 2) + Math.pow((bounds.minY + bounds.maxY) / 2 - py, 2)
                        + Math.pow((bounds.minZ + bounds.maxZ) / 2 - pz, 2));
                double softness = NestworldConfig.SPATIAL_SOFTNESS.get() / 100.0;
                half = Math.min(half, Math.max(1.0, toCentre * (0.75 - 0.5 * softness)));
                current.setSoundPlacement((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2,
                        (bounds.minZ + bounds.maxZ) / 2, right.getStepX() * half, 0, right.getStepZ() * half);
            }
        }
        nextUp = announce;
        AutoVsync.update(watching);
    }

    /**
     * Every viewer opens its own stream and a slow start leaves it behind the others for good. Compare the decoder with
     * the screen's shared timeline (game clock) and pull it back with a seek when it is clearly off.
     */
    private void correctDrift(BlockPos pos, VideoScreenBlockEntity screen, Display display, long gameTime) {
        long[] state = driftState.computeIfAbsent(pos, k -> new long[]{0, 0, 0});   // {off-since tick, last seek tick, last log tick}
        long expected = screen.getTimelinePositionMs(), actual = display.getPlaybackTime();
        if (expected < 0 || actual < 0 || !screen.isPlaying() || !display.isPlaying() || display.isBuffering() || display.getDuration() <= 0) {
            state[0] = 0; return;
        }
        long drift = actual - expected;
        if (NestworldConfig.DEBUG_STATS.get() && gameTime - state[2] >= 40) {
            state[2] = gameTime;
            com.mojang.logging.LogUtils.getLogger().info("Nestworld Video sync: this player is {}ms {} the shared timeline ({}ms vs {}ms)",
                    Math.abs(drift), drift < 0 ? "BEHIND" : "ahead of", actual, expected);
        }
        if (Math.abs(drift) < 2000) { state[0] = 0; return; }
        if (state[0] == 0) state[0] = gameTime;
        // Off for a second and not corrected in the last 15 s (a seek takes a moment to land).
        if (gameTime - state[0] >= 20 && gameTime - state[1] >= 300) {
            state[1] = gameTime; state[0] = 0;
            com.mojang.logging.LogUtils.getLogger().info("Nestworld Video: re-syncing with the other viewers ({}ms off)", drift);
            display.syncPosition(expected);
        }
    }

    private static java.util.List<com.nestworld.video.Category> categories() {
        var config = ClientData.getVideoConfig();
        return config == null || config.getCategories() == null ? java.util.List.of() : config.getCategories();
    }

    /** Remember progress, "watched" and the quality of a catalogue video for the library's resume shelf and marks. */
    private void recordHistory(BlockPos pos, Display display, boolean ended) {
        try {
            String url = display.getUrl();
            for (var category : categories()) {
                if (category == null || category.getVideos() == null) continue;
                for (var video : category.getVideos()) if (video != null && url.equals(video.getUrl())) {
                    var size = display.getDimensions();
                    long time = ended ? display.getDuration() : display.getPlaybackTime();
                    if (time < 0) return;
                    WatchHistory.record(category, video, time, display.getDuration(), size == null ? 0 : size.height, ended);
                    return;
                }
            }
        } catch (RuntimeException ignored) { }
    }

    public void shutdown() {
        AutoVsync.release();
        if (Minecraft.getInstance().getConnection() != null)
            for (BlockPos pos : lastViewerReports.keySet())
                Networking.INSTANCE.sendToServer(new PacketScreenWatch(pos, false));
        WatchHistory.saveNow();
        activeDisplays.values().forEach(Display::release);
        activeDisplays.clear();
        desiredScreens.clear();
        lastViewerReports.clear();
        lastHistoryRecords.clear();
        nextUp = null;
    }
}
