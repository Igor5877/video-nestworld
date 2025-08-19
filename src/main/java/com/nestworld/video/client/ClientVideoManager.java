package com.nestworld.video.client;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ClientVideoManager {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ClientVideoManager INSTANCE;
    
    private final MediaPlayerFactory factory;
    private final Map<BlockPos, VideoPlayerInstance> activePlayers = new ConcurrentHashMap<>();
    
    private ClientVideoManager() {
        this.factory = new MediaPlayerFactory();
        LOGGER.info("VLCJ Video Manager initialized");
    }
    
    public static ClientVideoManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ClientVideoManager();
        }
        return INSTANCE;
    }
    
    public void playVideo(BlockPos pos, String url) {
        stopVideo(pos); // Зупиняємо попереднє відео якщо є
        
        try {
            EmbeddedMediaPlayer mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();
            VideoPlayerInstance instance = new VideoPlayerInstance(mediaPlayer, url);
            
            // Налаштовуємо обробник подій
            mediaPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override
                public void playing(MediaPlayer mediaPlayer) {
                    LOGGER.info("Video started playing at position {}", pos);
                }
                
                @Override
                public void error(MediaPlayer mediaPlayer) {
                    LOGGER.error("Video playback error at position {}", pos);
                }
                
                @Override
                public void finished(MediaPlayer mediaPlayer) {
                    LOGGER.info("Video finished at position {}", pos);
                }
            });
            
            activePlayers.put(pos, instance);
            
            // Запускаємо відео
            mediaPlayer.media().play(url);
            
        } catch (Exception e) {
            LOGGER.error("Failed to start video playback at {}", pos, e);
        }
    }
    
    public void pauseVideo(BlockPos pos) {
        VideoPlayerInstance instance = activePlayers.get(pos);
        if (instance != null && instance.mediaPlayer != null) {
            instance.mediaPlayer.controls().pause();
        }
    }
    
    public void resumeVideo(BlockPos pos) {
        VideoPlayerInstance instance = activePlayers.get(pos);
        if (instance != null && instance.mediaPlayer != null) {
            instance.mediaPlayer.controls().play();
        }
    }
    
    public void stopVideo(BlockPos pos) {
        VideoPlayerInstance instance = activePlayers.remove(pos);
        if (instance != null && instance.mediaPlayer != null) {
            instance.mediaPlayer.controls().stop();
            instance.mediaPlayer.release();
        }
    }
    
    public BufferedImage getCurrentFrame(BlockPos pos) {
        VideoPlayerInstance instance = activePlayers.get(pos);
        if (instance != null) {
            return instance.getCurrentFrame();
        }
        return null;
    }
    
    public void shutdown() {
        activePlayers.values().forEach(instance -> {
            if (instance.mediaPlayer != null) {
                instance.mediaPlayer.release();
            }
        });
        activePlayers.clear();
        
        if (factory != null) {
            factory.release();
        }
    }
    
    private static class VideoPlayerInstance {
        final EmbeddedMediaPlayer mediaPlayer;
        final String url;
        BufferedImage currentFrame;
        
        VideoPlayerInstance(EmbeddedMediaPlayer mediaPlayer, String url) {
            this.mediaPlayer = mediaPlayer;
            this.url = url;
        }
        
        BufferedImage getCurrentFrame() {
            // TODO: Реалізувати отримання поточного кадру від VLCJ
            // Це потребує налаштування video output callback
            return currentFrame;
        }
    }
}