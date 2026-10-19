package com.nestworld.video.session;

import java.util.UUID;

/** The pure decision of session protection, kept free of Minecraft types so it can be unit tested. */
public final class SessionRules {
    private SessionRules() {}

    /**
     * @param ownerOnline    the owner is in the game right now
     * @param msSinceLeft    how long ago the offline owner left, or -1 when unknown (e.g. after a server restart)
     */
    public static boolean allowed(boolean protection, boolean operator, UUID owner, UUID me, boolean active,
                                  boolean ownerOnline, long msSinceLeft, long graceMs) {
        if (!protection || operator || owner == null || owner.equals(me) || !active) return true;
        if (ownerOnline) return false;
        return msSinceLeft < 0 || msSinceLeft >= graceMs;
    }

    /** Milliseconds until the protection lapses for an offline owner (0 when already free); -1 when the owner is online. */
    public static long graceLeftMs(boolean ownerOnline, long msSinceLeft, long graceMs) {
        if (ownerOnline) return -1;
        return msSinceLeft < 0 ? 0 : Math.max(0, graceMs - msSinceLeft);
    }
}
