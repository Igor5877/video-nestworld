package com.nestworld.video.session;

/** Who may control a screen that has an owner (the player who built it). Operators and the owner always may. */
public enum ScreenAccess {
    /** Everybody near the screen, with session protection while a video plays (as before screens had owners). */
    ALL("Усі"),
    /** The owner and the players on the screen's list. */
    LIST("Список"),
    /** Only the owner. */
    OWNER("Тільки власник");

    public final String label;
    ScreenAccess(String label) { this.label = label; }

    public static ScreenAccess byName(String name) {
        for (ScreenAccess access : values()) if (access.name().equalsIgnoreCase(name)) return access;
        return ALL;
    }

    public ScreenAccess next() { return values()[(ordinal() + 1) % values().length]; }
}
