package com.nestworld.video.client.util;

import com.nestworld.video.NestworldConfig;
import net.minecraftforge.fml.ModList;

import java.awt.Dimension;

/**
 * One video stream shown on a screen. The decoder behind it is chosen by {@link #create(String)}:
 * WaterMedia/VLC, the bundled-on-demand FFmpeg decoder, or VLC with an automatic FFmpeg fallback.
 */
public abstract class Display {

    public static Display create(String url) { return create(url, url); }

    /** {@code url} identifies the video; {@code source} is the address actually opened (the server's relay, or the same URL). */
    public static Display create(String url, String source) {
        // WaterMedia is optional: without it the FFmpeg decoder is the only choice.
        if (!ModList.get().isLoaded("watermedia")) return new FfmpegDisplay(url, source);
        return switch (NestworldConfig.DECODER.get()) {
            case "ffmpeg" -> new FfmpegDisplay(url, source);
            case "watermedia" -> new VlcDisplay(url, source);
            default -> new AutoDisplay(url, source);
        };
    }

    /** The address this display really reads from; differs from {@link #getUrl()} while the relay is used. */
    public String getSourceUrl() { return getUrl(); }

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
    /**
     * Tells the decoder where the screen is so it can play the sound from there: its centre and the vector from the
     * centre to the right-hand speaker. Decoders that cannot position sound ignore it.
     */
    /**
     * Fraction of the texture (width, height) that holds the visible picture. Decoders may allocate larger frame buffers
     * whose extra rows/columns contain garbage; the renderer must not map them onto the screen.
     */
    public float[] uvScale() { return new float[]{1f, 1f}; }
    /** True when the decoder sets the texture filter itself (mipmaps), so the renderer must not override it. */
    public boolean managesTextureFilter() { return false; }
    /** True when the current texture has a full mipmap chain. */
    public boolean hasMipmaps() { return false; }
    public void setSoundPlacement(double cx, double cy, double cz, double rx, double ry, double rz) {}
    /** Room acoustics for this screen's sound (null = none); only the FFmpeg audio path uses it. */
    public void setAcoustics(com.nestworld.video.client.acoustics.AcousticResult result) {}
    /** The current picture as a coarse grid of average colours, or null when unknown (only the FFmpeg path provides it). */
    public com.nestworld.video.client.glow.GlowGrid glowGrid() { return null; }
    public abstract void release();
}
