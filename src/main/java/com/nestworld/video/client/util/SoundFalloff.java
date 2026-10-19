package com.nestworld.video.client.util;

/**
 * How loud a screen is for a listener at a given distance. A bigger screen is audible from farther away, but it is never
 * louder at the screen itself: the size only stretches the distances, the gain at close range is always 1.
 */
public final class SoundFalloff {
    private SoundFalloff() {}

    /** Distance (from the screen's surface) up to which the screen is heard at full level. */
    public static double fullRadius(double screenDiagonal) {
        return clamp(6.0 + 0.6 * screenDiagonal, 6.0, 48.0);
    }

    /** Distance beyond which the screen is silent, never more than the distance at which streams run at all. */
    public static double maxRadius(double screenDiagonal, double streamLimit) {
        double wanted = clamp(24.0 + 3.0 * screenDiagonal, 24.0, 192.0);
        return Math.max(fullRadius(screenDiagonal) + 4.0, Math.min(wanted, streamLimit));
    }

    /** Gain factor from 0 to 1. Both ends of the slope are smooth, so nothing cuts in or out abruptly. */
    public static double factor(double distance, double screenDiagonal, double streamLimit) {
        double full = fullRadius(screenDiagonal), max = maxRadius(screenDiagonal, streamLimit);
        if (distance <= full) return 1.0;
        if (distance >= max) return 0.0;
        double t = (distance - full) / (max - full);
        double smooth = t * t * (3.0 - 2.0 * t);
        return Math.pow(1.0 - smooth, 1.5);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
