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
            Display display = Display.create(url);
            display.syncPosition(screenPosition(pos));
            activeDisplays.put(pos, display);
        } catch (RuntimeException exception) {
            com.mojang.logging.LogUtils.getLogger().warn("Unable to start video at {}", pos, exception);
        }
    }

    public void stopPlaying(BlockPos pos) {
        reportViewer(pos, false);
        lastEndReports.remove(pos);
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
        desiredScreens.put(pos, screen.getCurrentVideoUrl());
        if (existing != null && !existing.getUrl().equals(screen.getCurrentVideoUrl())) {
            stopPlaying(pos); existing = null;
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
