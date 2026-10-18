package com.nestworld.video.client.util;

import com.nestworld.video.NestworldConfig;
import net.minecraftforge.fml.ModList;

import java.awt.Dimension;

/**
 * One video stream shown on a screen. The decoder behind it is chosen by {@link #create(String)}:
 * WaterMedia/VLC, the bundled-on-demand FFmpeg decoder, or VLC with an automatic FFmpeg fallback.
 */
public abstract class Display {

    public static Display create(String url) {
        // WaterMedia is optional: without it the FFmpeg decoder is the only choice.
        if (!ModList.get().isLoaded("watermedia")) return new FfmpegDisplay(url);
        return switch (NestworldConfig.DECODER.get()) {
            case "ffmpeg" -> new FfmpegDisplay(url);
            case "watermedia" -> new VlcDisplay(url);
            default -> new AutoDisplay(url);
        };
    }

    public abstract String getUrl();
    /** GL texture id of the current frame, or -1 while nothing can be drawn yet. */
    public abstract int getTextureId();
    /** Housekeeping on the client tick, never from the renderer. */
    public abstract void tick();
    public abstract void setPlaying(boolean playing);
    public abstract boolean isPlaying();
    public abstract void syncPosition(long positionMs);
    public abstract boolean needsInitialSync();
    public abstract boolean isLoading();
    public abstract boolean isBuffering();
    public abstract boolean isBroken();
    public abstract boolean isEnded();
    /** True exactly once, after a video that actually showed frames has played to its end. */
    public abstract boolean pollEnded();
    public abstract long getDuration();
    public abstract long getPlaybackTime();
    public abstract Dimension getDimensions();
    public abstract void setVolume(int volume);
    public abstract int getVolume();
    public abstract void applySpatialVolume(double factor);
    public abstract void release();
}
