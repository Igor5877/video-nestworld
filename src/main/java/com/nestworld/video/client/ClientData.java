package com.nestworld.video.client;

import com.nestworld.video.VideoConfig;

public class ClientData {
    // Статичне поле, щоб мати доступ до конфігурації з будь-якого місця в клієнтському коді
    private static VideoConfig videoConfig;

    public static VideoConfig getVideoConfig() {
        return videoConfig;
    }

    public static void setVideoConfig(VideoConfig config) {
        videoConfig = config;
    }
}