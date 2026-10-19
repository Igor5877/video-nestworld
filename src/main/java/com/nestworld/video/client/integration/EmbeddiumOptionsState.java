package com.nestworld.video.client.integration;

/** Whether the Embeddium page was added. Kept apart from {@link EmbeddiumOptions} so that checking it never loads Embeddium classes. */
public final class EmbeddiumOptionsState {
    private static volatile boolean registered;
    private EmbeddiumOptionsState() {}
    public static boolean registered() { return registered; }
    static void set(boolean value) { registered = value; }
}
