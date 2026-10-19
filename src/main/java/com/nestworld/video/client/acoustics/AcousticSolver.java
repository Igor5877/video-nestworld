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
    public static final double ABSORPTION = 3.0;

    public record Reverb(float sendGain, float sendHf, float decayTime) {}
    public record Direct(float gainL, float hfL, float gainR, float hfR) {}

    /** Least blocked path from any probe to the listener, turned into a low-pass (gain, high-frequency gain). */
    public static float[] directFilter(RayWorld world, List<V3> probes, V3 listener) {
        double occlusion = MAX_OCCLUSION;
        for (V3 probe : probes) {
            occlusion = Math.min(occlusion, world.occlusion(probe, listener, MAX_OCCLUSION));
            if (occlusion <= 0) break;
        }
        double hf = Math.max(0.02, Math.exp(-ABSORPTION * occlusion));
        double gain = Math.pow(hf, 0.12);
        return new float[]{(float) gain, (float) hf};
    }

    public static Direct direct(RayWorld world, List<V3> left, List<V3> right, V3 listener) {
        float[] l = directFilter(world, left, listener), r = directFilter(world, right, listener);
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
                // What reaches the listener from this surface (in sight) goes into the reverb, weaker the longer the way.
                if (world.occlusion(point, listener, 0.2) < 0.05)
                    contribution += energy / (1.0 + (pathLength + point.distanceTo(listener)) / 8.0);
                dir = dir.reflect(hit.normal()).normalized();
                position = point;
            }
        }
        double e = contribution / Math.max(1, rays);
        double enclosure = 1.0 - (double) escaped / Math.max(1, rays);
        double averageFree = segments == 0 ? maxDist : freeSum / segments;
        double averageReflect = hits == 0 ? 0.5 : reflectSum / hits;
        float send = (float) clamp(amount * e * 0.65, 0, 1);
        float hf = (float) clamp(0.4 + 0.55 * averageReflect, 0.3, 1.0);
        float decay = (float) clamp((0.25 + 0.1 * averageFree) * (0.4 + 0.6 * enclosure), 0.3, 4.0);
        return new Reverb(send, hf, decay);
    }

    public static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
}
