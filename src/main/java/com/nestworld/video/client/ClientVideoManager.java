package com.nestworld.video.client;

import com.nestworld.video.client.util.Display;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketControlVideo;
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
        for (Map.Entry<BlockPos, Display> entry : activeDisplays.entrySet()) {
            Display display = entry.getValue();
            if (display.pollEnded()) {
                // Сервер сам вирішує, яка серія наступна
                Networking.INSTANCE.sendToServer(
                        new PacketControlVideo(entry.getKey(), "ended", display.getUrl(), ""));
            }
        }
    }

    public void shutdown() {
        activeDisplays.values().forEach(Display::release);
        activeDisplays.clear();
    }
}
