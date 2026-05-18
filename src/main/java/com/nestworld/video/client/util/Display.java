package com.nestworld.video.client.util;

import net.minecraft.client.Minecraft;
import org.watermedia.api.player.videolan.VideoPlayer;

import java.awt.Dimension;
import java.net.URI;
import java.net.URISyntaxException;

public class Display {

    private final VideoPlayer player;
    private final String url;
    private boolean isPlaying = false;

    // Cache preRender() result for one render frame so that all tiles in a multi-block
    // screen share the same GL texture ID and never show different video frames.
    private int cachedTextureId = -1;
    private long lastPreRenderNanos = 0;
    // 8 ms ≈ one frame at 125 fps; safe cache window for any reasonable frame rate.
    private static final long FRAME_CACHE_NANOS = 8_000_000L;

    public Display(String url) {
        this.url = url;
        this.player = new VideoPlayer(Minecraft.getInstance());

        try {
            this.player.start(new URI(url));
            this.setPlaying(true);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    public int getTextureId() {
        if (!player.isReady()) return -1;
        long now = System.nanoTime();
        if (cachedTextureId == -1 || now - lastPreRenderNanos > FRAME_CACHE_NANOS) {
            cachedTextureId = player.preRender();
            lastPreRenderNanos = now;
        }
        return cachedTextureId;
    }

    public void setPlaying(boolean playing) {
        this.isPlaying = playing;
        if (player.isReady()) {
            player.setPauseMode(!playing);
        }
    }

    public boolean isPlaying() {
        return isPlaying && player.isPlaying();
    }

    public Dimension getDimensions() {
        if (player.isReady()) {
            return player.dimension();
        }
        return new Dimension(0, 0);
    }
    
    private int baseVolume = 100;

    public void setVolume(int volume) {
        baseVolume = Math.max(0, Math.min(200, volume));
    }

    public int getVolume() {
        return baseVolume;
    }

    void applySpatialVolume(double factor) {
        int effective = (int) (baseVolume * factor);
        player.setVolume(Math.max(0, Math.min(200, effective)));
    }

    public void release() {
        player.stop();
        player.release();
    }
}