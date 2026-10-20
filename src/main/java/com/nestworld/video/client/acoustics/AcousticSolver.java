package com.nestworld.video.client.acoustics;

import java.util.List;

/**
 * Own, clean implementation of the room acoustics of a screen (not derived from any other mod's code):
 * <ul>
 *   <li><b>direct sound</b>: the blocking of the materials between each speaker and the listener, taking the least
 *       blocked of several probe points; exponential in the blocking, like sound through a wall;</li>
 *   <li><b>reverb</b>: rays leave the screen into the room and bounce off its surfaces; what reaches the listener
 *       in sight feeds the reverb, how far the rays travel sets the decay time, rays that escape (open sky) count
 *       as no room at all.</li>
 * </ul>
 */
public final class AcousticSolver {
    private AcousticSolver() {}

    /** Largest blocking that matters: beyond it everything is already muffled. */
    public static final double MAX_OCCLUSION = 6.0;
    /** Muffling strength per unit of blocking (1 block of stone leaves about a fifth of the high frequencies). */
    public static final double ABSORPTION = 1.5;
    /** How much of the loudness follows the high-frequency loss: low enough to keep speech audible behind a wall. */
    private static final double GAIN_EXPONENT = 0.25;

    /** @param enclosure 0 (open sky) .. 1 (closed room): how much of the room's sound there is */
    public record Reverb(float sendGain, float sendHf, float decayTime, float enclosure) {
        public Reverb(float sendGain, float sendHf, float decayTime) { this(sendGain, sendHf, decayTime, 0f); }
    }

    /**
     * In a room the direct sound of the screen falls off with distance (half the pressure per doubling) while the
     * room's reverberant sound stays about the same everywhere: the back rows of a cinema hear less screen and more
     * hall. Outdoors there is no hall, there the plain falloff applies. Returns the factor for the direct sound.
     *
     * @param distance       from the listener to the screen's surface
     * @param screenDiagonal in blocks: a bigger screen is a bigger source, its sound starts falling off farther away
     */
    public static double directDistanceFactor(double distance, double screenDiagonal, double enclosure) {
        double reference = Math.max(6.0, 4.0 + 0.3 * screenDiagonal);
        double inverse = Math.max(0.4, reference / Math.max(reference, distance));
        // Under the open sky about half of the rays still hit the ground: only a mostly closed room counts as a hall.
        double hall = clamp((enclosure - 0.6) / 0.3, 0, 1);
        return 1.0 - hall * (1.0 - inverse);
    }
    public record Direct(float gainL, float hfL, float gainR, float hfR) {}

    /** Least blocked path from any probe to the listener, turned into a low-pass (gain, high-frequency gain). */
    public static float[] directFilter(RayWorld world, List<V3> probes, V3 listener) { return directFilter(world, probes, listener, ABSORPTION); }

    public static float[] directFilter(RayWorld world, List<V3> probes, V3 listener, double absorption) {
        double occlusion = MAX_OCCLUSION;
        for (V3 probe : probes) {
            occlusion = Math.min(occlusion, world.occlusion(probe, listener, MAX_OCCLUSION));
            if (occlusion <= 0) break;
        }
        double hf = Math.max(0.02, Math.exp(-absorption * occlusion));
        double gain = Math.pow(hf, GAIN_EXPONENT);
        return new float[]{(float) gain, (float) hf};
    }

    public static Direct direct(RayWorld world, List<V3> left, List<V3> right, V3 listener) { return direct(world, left, right, listener, ABSORPTION); }

    public static Direct direct(RayWorld world, List<V3> left, List<V3> right, V3 listener, double absorption) {
        float[] l = directFilter(world, left, listener, absorption), r = directFilter(world, right, listener, absorption);
        return new Direct(l[0], l[1], r[0], r[1]);
    }

    /**
     * @param origin    where the rays start (in front of the screen)
     * @param front     unit direction the screen faces
     * @param amount    0..1 strength of the reverb chosen by the player
     */
    public static Reverb reverb(RayWorld world, V3 origin, V3 front, V3 listener, int rays, int bounces, double amount, double maxDist) {
        V3 up = Math.abs(front.y()) > 0.9 ? new V3(1, 0, 0) : new V3(0, 1, 0);
        V3 t1 = front.cross(up).normalized(), t2 = front.cross(t1).normalized();
        double contribution = 0, freeSum = 0, reflectSum = 0;
        int segments = 0, escaped = 0, hits = 0;
        for (int i = 0; i < rays; i++) {
            double z = (i + 0.5) / rays;                       // cosine of the angle to the front: covers the half space evenly
            double phi = i * 2.399963229728653;                // golden angle
            double r = Math.sqrt(Math.max(0, 1 - z * z));
            V3 dir = t1.scale(r * Math.cos(phi)).add(t2.scale(r * Math.sin(phi))).add(front.scale(z)).normalized();
            V3 position = origin;
            double pathLength = 0, energy = 1;
            for (int b = 0; b <= bounces; b++) {
                RayWorld.Hit hit = world.cast(position, dir, maxDist);
                if (hit == null) {
                    if (b == 0) { escaped++; freeSum += maxDist; segments++; }
                    break;
                }
                segments++; hits++;
                freeSum += hit.distance();
                reflectSum += hit.reflectivity();
                pathLength += hit.distance();
                energy *= Math.min(1.0, Math.max(0.0, hit.reflectivity()));
                V3 point = hit.point().add(hit.normal().scale(0.05));
                // What reaches the listener from this surface (in sight) goes into the reverb, weaker the longer the
                // sound has travelled from the screen. Not much weaker for a listener far from the screen: a hall's
                // reverberant sound fills it evenly, the back rows hear as much of it as the front ones.
                if (world.occlusion(point, listener, 0.2) < 0.05)
                    contribution += energy / (1.0 + (pathLength + 0.25 * point.distanceTo(listener)) / 16.0);
                dir = dir.reflect(hit.normal()).normalized();
                position = point;
            }
        }
        double e = contribution / Math.max(1, rays);
        double enclosure = 1.0 - (double) escaped / Math.max(1, rays);
        double averageFree = segments == 0 ? maxDist : freeSum / segments;
        double averageReflect = hits == 0 ? 0.5 : reflectSum / hits;
        float send = (float) clamp(amount * e * 1.0, 0, 1);
        float hf = (float) clamp(0.4 + 0.55 * averageReflect, 0.3, 1.0);
        // Sabine: decay time ~ 0.161 V / (S a) = 0.04 * mean free path / absorption. A stone hall rings for seconds, a
        // wooden room for half a second, wool swallows it; rays escaping to the sky shorten it.
        double absorptionCoefficient = Math.max(0.05, 1.0 - averageReflect);
        float decay = (float) clamp(0.04 * averageFree / absorptionCoefficient * (0.3 + 0.7 * enclosure), 0.3, 3.5);
        return new Reverb(send, hf, decay, (float) enclosure);
    }

    public static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
}
