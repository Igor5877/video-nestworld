package com.nestworld.video.client;

import com.nestworld.video.client.util.Display;
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
        // This can be used later to update players, e.g. for time sync
    }

    public void shutdown() {
        activeDisplays.values().forEach(Display::release);
        activeDisplays.clear();
    }
}
