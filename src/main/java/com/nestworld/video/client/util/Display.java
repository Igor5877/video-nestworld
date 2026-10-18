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
    private boolean endReported = false;

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
        if (player.isReady()) {
            return player.preRender();
        }
        return -1;
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

    public String getUrl() {
        return url;
    }

    /** Returns true exactly once, when the video has played to its end. */
    public boolean pollEnded() {
        if (!endReported && isPlaying && player.isEnded()) {
            endReported = true;
            return true;
        }
        return false;
    }

    public Dimension getDimensions() {
        if (player.isReady()) {
            return player.dimension();
        }
        return new Dimension(0, 0);
    }
    
    public void release() {
        player.stop();
        player.release();
    }
}