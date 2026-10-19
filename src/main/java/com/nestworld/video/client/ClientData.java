package com.nestworld.video.client;

import com.nestworld.video.VideoConfig;

public class ClientData {
    // Статичне поле, щоб мати доступ до конфігурації з будь-якого місця в клієнтському коді
    private static VideoConfig videoConfig;

    public static VideoConfig getVideoConfig() {
        return videoConfig;
    }

    /** May this player watch 18+ videos? Set by the server; false until it says otherwise. */
    private static volatile boolean adultAllowed;
    public static boolean isAdultAllowed() { return adultAllowed; }
    public static void setAdultAllowed(boolean allowed) { adultAllowed = allowed; }

    public static void setVideoConfig(VideoConfig config) {
        videoConfig = config;
    }
}