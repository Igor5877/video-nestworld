package com.nestworld.video.client;

import com.nestworld.video.client.util.Display;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientVideoManager {

    private static final ClientVideoManager INSTANCE = new ClientVideoManager();
    private final Map<BlockPos, Display> activeDisplays = new ConcurrentHashMap<>();

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

        Display display = new Display(url);
        activeDisplays.put(pos, display);
    }

    public void stopPlaying(BlockPos pos) {
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

    public Display getDisplay(BlockPos pos) {
        return activeDisplays.get(pos);
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double px = mc.player.getX();
        double py = mc.player.getEyeY();
        double pz = mc.player.getZ();
        for (Map.Entry<BlockPos, Display> entry : activeDisplays.entrySet()) {
            BlockPos pos = entry.getKey();
            double dx = pos.getX() + 0.5 - px;
            double dy = pos.getY() + 0.5 - py;
            double dz = pos.getZ() + 0.5 - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            entry.getValue().applySpatialVolume(spatialFactor(dist));
        }
    }

    private static double spatialFactor(double dist) {
        final double FULL = 6.0, MAX = 24.0;
        if (dist <= FULL) return 1.0;
        if (dist >= MAX) return 0.0;
        double t = (dist - FULL) / (MAX - FULL);
        return (1.0 - t) * (1.0 - t);
    }

    public void shutdown() {
        activeDisplays.values().forEach(Display::release);
        activeDisplays.clear();
    }
}
