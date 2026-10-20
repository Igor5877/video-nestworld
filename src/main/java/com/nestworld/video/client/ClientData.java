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
    /** The server lets this player control every screen (operator or nestworldvideo.control). */
    private static volatile boolean controlAllowed;
    public static boolean isControlAllowed() { return controlAllowed; }
    public static void setControlAllowed(boolean allowed) { controlAllowed = allowed; }

    public static void setVideoConfig(VideoConfig config) {
        videoConfig = config;
    }
}