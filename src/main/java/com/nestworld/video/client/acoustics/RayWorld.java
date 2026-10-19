package com.nestworld.video.client.acoustics;

/** What the acoustic solver needs to know about the world (implemented on top of the loaded blocks, or by a test). */
public interface RayWorld {
    /** First surface along the ray from {@code origin} (unit {@code dir}) within {@code maxDist}, or null when the ray escapes. */
    Hit cast(V3 origin, V3 dir, double maxDist);

    /** Total sound blocking of the materials between {@code a} and {@code b}; every solid cell counts once; stops at {@code cap}. */
    double occlusion(V3 a, V3 b, double cap);

    record Hit(V3 point, V3 normal, double distance, double reflectivity) {}
}
