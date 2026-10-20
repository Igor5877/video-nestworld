package com.nestworld.video.client.util;

import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

import java.awt.Dimension;

/**
 * The last picture of a screen whose decoder was closed because other screens are closer or looked at: it is drawn like
 * a paused video, costs no decoding and no network, and owns its texture.
 */
public final class StillDisplay extends Display {
    private final String url;
    private final Dimension size;
    private final float[] uv;
    private final boolean mipmapped;
    private int textureId;

    public StillDisplay(String url, int textureId, Dimension size, float[] uv, boolean mipmapped) {
        this.url = url; this.textureId = textureId; this.size = size; this.uv = uv.clone(); this.mipmapped = mipmapped;
    }

    @Override public String getUrl() { return url; }
    @Override public int getTextureId() { return textureId > 0 ? textureId : -1; }
    @Override public void tick() {}
    @Override public void setPlaying(boolean playing) {}
    @Override public boolean isPlaying() { return false; }
    @Override public void syncPosition(long positionMs) {}
    @Override public boolean needsInitialSync() { return false; }
    @Override public boolean isLoading() { return false; }
    @Override public boolean isBuffering() { return false; }
    @Override public boolean isBroken() { return false; }
    @Override public boolean isEnded() { return false; }
    @Override public boolean pollEnded() { return false; }
    @Override public long getDuration() { return -1; }
    @Override public long getPlaybackTime() { return -1; }
    @Override public Dimension getDimensions() { return size; }
    @Override public void setVolume(int volume) {}
    @Override public int getVolume() { return 0; }
    @Override public void applySpatialVolume(double factor) {}
    @Override public float[] uvScale() { return uv.clone(); }
    @Override public boolean managesTextureFilter() { return true; }
    @Override public boolean hasMipmaps() { return mipmapped; }

    @Override public void release() {
        int id = textureId;
        textureId = 0;
        if (id <= 0) return;
        if (RenderSystem.isOnRenderThread()) TextureUtil.releaseTextureId(id);
        else Minecraft.getInstance().execute(() -> TextureUtil.releaseTextureId(id));
    }
}
