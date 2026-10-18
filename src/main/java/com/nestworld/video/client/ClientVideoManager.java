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
            Display display = new Display(url);
            display.syncPosition(screenPosition(pos));
            activeDisplays.put(pos, display);
        } catch (RuntimeException exception) {
            com.mojang.logging.LogUtils.getLogger().warn("Unable to start video at {}", pos, exception);
        }
    }

    public void stopPlaying(BlockPos pos) {
        reportViewer(pos, false);
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
            if (current != null && screen.isPlaying() && current.pollEnded())
                Networking.INSTANCE.sendToServer(new PacketVideoEnded(pos, current.getUrl()));
            if (current != null)
                screen.setClientPlaybackAdvancing(current.isPlaying() && !current.isBuffering());
            if (current != null && current.needsInitialSync())
                current.syncPosition(screen.getPlaybackPositionMs());
            long now = mc.level.getGameTime();
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
            if (current != null) current.applySpatialVolume(spatialFactor(dist));
        }
    }

    private static double spatialFactor(double dist) {
        // Keep full volume close to the wall, then make the falloff audible over a
        // useful outdoor distance instead of cutting off almost immediately.
        final double FULL = 16.0, MAX = 128.0;
        if (dist <= FULL) return 1.0;
        if (dist >= MAX) return 0.0;
        double t = (dist - FULL) / (MAX - FULL);
        return Math.pow(1.0 - t, 1.35);
    }

    public void shutdown() {
        if (Minecraft.getInstance().getConnection() != null)
            for (BlockPos pos : lastViewerReports.keySet())
                Networking.INSTANCE.sendToServer(new PacketScreenWatch(pos, false));
        activeDisplays.values().forEach(Display::release);
        activeDisplays.clear();
        desiredScreens.clear();
        lastViewerReports.clear();
    }
}
