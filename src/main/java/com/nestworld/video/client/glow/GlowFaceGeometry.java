package com.nestworld.video.client.glow;

/** Geometry shared by the lighting solver and its seam regression check. */
final class GlowFaceGeometry {
    private static final int[][] NORMAL = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
            {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] SIGNS = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};

    private GlowFaceGeometry() {}

    /** The face bordering the lit air cell. Coplanar faces must share exactly the same edges. */
    static double[] corner(int x, int y, int z, int direction, int corner) {
        int[] d = NORMAL[direction];
        int[] t1 = d[0] != 0 ? new int[]{0, 1, 0} : new int[]{1, 0, 0};
        int[] t2 = d[2] != 0 ? new int[]{0, 1, 0} : new int[]{0, 0, 1};
        int s1 = SIGNS[corner][0], s2 = SIGNS[corner][1];
        // Do not push vertices into the air. That makes perpendicular faces overlap in
        // a bright strip at inside corners. The render type applies a depth bias instead.
        return new double[]{
                x + 0.5 + d[0] * 0.5 + (t1[0] * s1 + t2[0] * s2) * 0.5,
                y + 0.5 + d[1] * 0.5 + (t1[1] * s1 + t2[1] * s2) * 0.5,
                z + 0.5 + d[2] * 0.5 + (t1[2] * s1 + t2[2] * s2) * 0.5};
    }
}
