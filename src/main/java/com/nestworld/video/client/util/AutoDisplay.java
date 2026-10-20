package com.nestworld.video.client.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.awt.Dimension;

/** Starts with WaterMedia/VLC and hands the stream over to FFmpeg if VLC cannot play it. */
final class AutoDisplay extends Display {
    private static final Logger LOGGER = LogUtils.getLogger();

    private Display delegate;
    private final String url, source;
    private boolean fellBack;
    private boolean playing = true;
    private int volume = 100;
    private double spatial = 1.0;

    AutoDisplay(String url) { this(url, url); }

    AutoDisplay(String url, String source) {
        this.url = url; this.source = source;
        Display first;
        try {
            first = new VlcDisplay(url, source);
            this.delegate = first;
        } catch (RuntimeException e) {
            LOGGER.warn("Nestworld Video: VLC could not start ({}), using FFmpeg", e.toString());
            fallBack();
        }
    }

    private void fallBack() {
        Display old = delegate;
        fellBack = true;
        delegate = new FfmpegDisplay(url, source);
        delegate.setVolume(volume);
        delegate.applySpatialVolume(spatial);
        delegate.setPlaying(playing);
        if (old != null) {
            try { old.release(); } catch (RuntimeException ignored) { /* already failed */ }
        }
    }

    @Override public void tick() {
        delegate.tick();
        if (!fellBack && delegate.isBroken()) {
            LOGGER.warn("Nestworld Video: VLC could not play {}; switching to the FFmpeg decoder", url.contains("?") ? url.substring(0, url.indexOf('?')) + "?<redacted>" : url);
            fallBack();
        }
    }

    @Override public String getUrl() { return url; }
    @Override public String getSourceUrl() { return source; }
    @Override public int getTextureId() { return delegate.getTextureId(); }
    @Override public void setPlaying(boolean value) { playing = value; delegate.setPlaying(value); }
    @Override public boolean isPlaying() { return delegate.isPlaying(); }
    @Override public void syncPosition(long positionMs) { delegate.syncPosition(positionMs); }
    @Override public boolean needsInitialSync() { return delegate.needsInitialSync(); }
    @Override public boolean isLoading() { return delegate.isLoading(); }
    @Override public boolean isBuffering() { return delegate.isBuffering(); }
    /** While VLC is still being replaced the screen should keep showing the loading animation, not an error. */
    @Override public boolean isBroken() { return fellBack && delegate.isBroken(); }
    @Override public boolean isEnded() { return delegate.isEnded(); }
    @Override public boolean pollEnded() { return delegate.pollEnded(); }
    @Override public long getDuration() { return delegate.getDuration(); }
    @Override public long getPlaybackTime() { return delegate.getPlaybackTime(); }
    @Override public Dimension getDimensions() { return delegate.getDimensions(); }
    @Override public void setVolume(int value) { volume = value; delegate.setVolume(value); }
    @Override public int getVolume() { return delegate.getVolume(); }
    @Override public void applySpatialVolume(double factor) { spatial = factor; delegate.applySpatialVolume(factor); }
    @Override public void setSoundPlacement(double cx, double cy, double cz, double rx, double ry, double rz) {
        delegate.setSoundPlacement(cx, cy, cz, rx, ry, rz);
    }
    @Override public void setAcoustics(com.nestworld.video.client.acoustics.AcousticResult result) { delegate.setAcoustics(result); }
    @Override public com.nestworld.video.client.glow.GlowGrid glowGrid() { return delegate.glowGrid(); }
    @Override public float[] uvScale() { return delegate.uvScale(); }
    @Override public boolean managesTextureFilter() { return delegate.managesTextureFilter(); }
    @Override public boolean hasMipmaps() { return delegate.hasMipmaps(); }
    @Override public long frameSerial() { return delegate.frameSerial(); }
    @Override public void setPlaybackRate(double rate) { delegate.setPlaybackRate(rate); }
    @Override public void release() { delegate.release(); }
}
