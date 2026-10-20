package com.nestworld.video.client.glow;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Standalone regression: javac this and GlowFaceGeometry, then run this class with -ea. */
public final class GlowGeometryCheck {
    private static Set<String> corners(int x, int y, int z, int d) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            double[] p = GlowFaceGeometry.corner(x, y, z, d, i);
            int axis = d / 2;
            int[] cell = {x, y, z};
            if (p[axis] != cell[axis] + (d % 2 == 0 ? 1 : 0))
                throw new AssertionError("Decal floated off its block face: " + Arrays.toString(p));
            for (int a = 0; a < 3; a++)
                if (p[a] != cell[a] && p[a] != cell[a] + 1)
                    throw new AssertionError("Corner outside exact block boundaries");
            result.add(Arrays.toString(p));
        }
        if (result.size() != 4) throw new AssertionError("Degenerate face");
        return result;
    }

    public static void main(String[] args) {
        for (int origin : new int[]{0, -17, 30_000_000}) {
            for (int d = 0; d < 6; d++) {
                Set<String> face = corners(origin, origin, origin, d);
                int[] neighbour = {origin, origin, origin};
                neighbour[(d / 2 + 1) % 3]++;
                Set<String> adjacent = corners(neighbour[0], neighbour[1], neighbour[2], d);
                adjacent.retainAll(face);
                if (adjacent.size() != 2) throw new AssertionError("Coplanar tiles do not share one exact edge");
                for (int other = 0; other < 6; other++) {
                    if (other / 2 == d / 2) continue;
                    Set<String> corner = corners(origin, origin, origin, other);
                    corner.retainAll(face);
                    if (corner.size() != 2) throw new AssertionError("Room corner has overlapping floating strips");
                }
            }
        }
        System.out.println("PASS: all six face directions, adjacent tiles and inside corners, including large coordinates");
    }
}
