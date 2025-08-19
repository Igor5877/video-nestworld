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