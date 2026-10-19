// Standalone check of the acoustic maths (no Minecraft needed). Run from the repository root:
//   javac -d /tmp/ac src/main/java/com/nestworld/video/client/acoustics/{V3,RayWorld,AcousticResult,AcousticSolver}.java tools/AcousticSolverCheck.java
//   java -cp /tmp/ac AcousticSolverCheck
import com.nestworld.video.client.acoustics.*;
import java.util.*;

public class AcousticSolverCheck {
  static int fail;
  static void c(String n, boolean ok, String info) { if (!ok) { fail++; System.out.println("FAIL " + n + " " + info); } else System.out.println("ok   " + n + " " + info); }

  /** Cells from -R..R in x/z and 0..H in y; material (occlusion, reflectivity) per cell, default air. */
  static class Grid implements RayWorld {
    final Map<Long, double[]> cells = new HashMap<>();
    static long key(int x, int y, int z) { return ((long) (x + 4096) << 40) ^ ((long) (y + 4096) << 20) ^ (z + 4096); }
    void set(int x, int y, int z, double occ, double refl) { cells.put(key(x, y, z), new double[]{occ, refl}); }
    void box(int x0, int y0, int z0, int x1, int y1, int z1, double occ, double refl) {
      for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) set(x, y, z, occ, refl);
    }
    /** Walk the cells along a→b; visitor gets (cell, entry t, axis stepped (0..2, -1 start), step sign). Returns when it says stop. */
    interface Visit { boolean at(int x, int y, int z, double t, int axis, int sign); }
    void walk(V3 a, V3 b, Visit v) {
      double[] o = {a.x(), a.y(), a.z()}, d = {b.x() - a.x(), b.y() - a.y(), b.z() - a.z()};
      int[] cell = {(int) Math.floor(o[0]), (int) Math.floor(o[1]), (int) Math.floor(o[2])};
      int[] step = new int[3]; double[] tMax = new double[3], tDelta = new double[3];
      for (int i = 0; i < 3; i++) {
        step[i] = d[i] > 0 ? 1 : d[i] < 0 ? -1 : 0;
        if (step[i] == 0) { tMax[i] = Double.POSITIVE_INFINITY; tDelta[i] = Double.POSITIVE_INFINITY; }
        else { tDelta[i] = 1.0 / Math.abs(d[i]); tMax[i] = (step[i] > 0 ? cell[i] + 1 - o[i] : o[i] - cell[i]) * tDelta[i]; }
      }
      double t = 0; int axis = -1, sign = 0;
      for (int n = 0; n < 512; n++) {
        if (v.at(cell[0], cell[1], cell[2], t, axis, sign)) return;
        int m = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
        if (tMax[m] > 1.0) return;
        t = tMax[m]; cell[m] += step[m]; tMax[m] += tDelta[m]; axis = m; sign = step[m];
      }
    }
    public Hit cast(V3 origin, V3 dir, double maxDist) {
      V3 end = origin.add(dir.scale(maxDist));
      Hit[] out = new Hit[1];
      walk(origin, end, (x, y, z, t, axis, sign) -> {
        double[] m = cells.get(key(x, y, z));
        if (m == null) return false;
        V3 p = origin.add(dir.scale(maxDist * t));
        double[] n = {0, 0, 0};
        if (axis < 0) { int big = Math.abs(dir.x()) > Math.abs(dir.y()) ? (Math.abs(dir.x()) > Math.abs(dir.z()) ? 0 : 2) : (Math.abs(dir.y()) > Math.abs(dir.z()) ? 1 : 2); n[big] = -Math.signum(big == 0 ? dir.x() : big == 1 ? dir.y() : dir.z()); }
        else n[axis] = -sign;
        out[0] = new Hit(p, new V3(n[0], n[1], n[2]), maxDist * t, m[1]);
        return true;
      });
      return out[0];
    }
    public double occlusion(V3 a, V3 b, double cap) {
      double[] total = {0};
      walk(a, b, (x, y, z, t, axis, sign) -> { double[] m = cells.get(key(x, y, z)); if (m != null) total[0] += m[0]; return total[0] >= cap; });
      return Math.min(total[0], cap);
    }
  }

  static AcousticSolver.Direct direct(Grid g, V3 speaker, V3 listener) {
    List<V3> probes = List.of(speaker, speaker.add(new V3(0.3, 0, 0)), speaker.add(new V3(-0.3, 0, 0)), speaker.add(new V3(0, 0.3, 0)), speaker.add(new V3(0, -0.3, 0)));
    return AcousticSolver.direct(g, probes, probes, listener);
  }
  static AcousticSolver.Reverb reverb(Grid g, V3 origin, V3 front, V3 listener) {
    return AcousticSolver.reverb(g, origin, front, listener, 24, 3, 0.6, 48);
  }
  /** A closed hall 16 x 6 x 16 around (0..15, 0..5, 0..15), walls 1 thick made of the given material. */
  static Grid hall(double occ, double refl) {
    Grid g = new Grid();
    g.box(-1, -1, -1, 16, -1, 16, occ, refl); g.box(-1, 6, -1, 16, 6, 16, occ, refl);
    g.box(-1, 0, -1, -1, 5, 16, occ, refl); g.box(16, 0, -1, 16, 5, 16, occ, refl);
    g.box(0, 0, -1, 15, 5, -1, occ, refl); g.box(0, 0, 16, 15, 5, 16, occ, refl);
    return g;
  }

  public static void main(String[] a) {
    V3 front = new V3(0, 0, 1), origin = new V3(8.5, 2.5, 0.8);
    // 1. open field
    Grid open = new Grid(); open.box(-40, -1, -40, 40, -1, 40, 1.0, 0.3);   // just ground
    V3 listener = new V3(8.5, 1.7, 8.5);
    var d1 = direct(open, origin, listener);
    c("open: direct is clear", d1.gainL() > 0.99 && d1.hfL() > 0.99, d1.toString());
    var r1 = reverb(open, origin.add(new V3(0, 0, 0.5)), front, listener);
    c("open: almost no reverb", r1.sendGain() < 0.06, r1.toString());
    // 2. one stone wall between speaker and listener
    Grid wall = new Grid(); wall.box(-40, -1, -40, 40, -1, 40, 1.0, 0.3); wall.box(-20, 0, 4, 20, 10, 4, 1.0, 0.9);
    var d2 = direct(wall, origin, listener);
    c("wall: muffled", d2.hfL() < 0.3 && d2.gainL() < 0.85 && d2.gainL() > 0.4, d2.toString());
    // 3. doorway in the wall in line with the speaker
    wall.box(8, 0, 4, 9, 3, 4, 0, 0); wall.cells.keySet().removeIf(k -> false);
    for (int x = 8; x <= 9; x++) for (int y = 0; y <= 3; y++) wall.cells.remove(Grid.key(x, y, 4));
    var d3 = direct(wall, origin, listener);
    c("doorway: nearly clear again", d3.hfL() > 0.9, d3.toString());
    // 4. closed stone hall vs wool hall vs listener outside
    Grid stone = hall(1.0, 0.95), wool = hall(1.4, 0.1);
    V3 inside = new V3(8.5, 1.7, 9.5);
    var rs = reverb(stone, new V3(8.5, 2.5, 0.8), front, inside);
    var rw = reverb(wool, new V3(8.5, 2.5, 0.8), front, inside);
    c("stone hall: clear reverb", rs.sendGain() > 0.12 && rs.sendGain() < 0.6, rs.toString());
    c("stone hall beats wool hall", rs.sendGain() > rw.sendGain() * 1.8 && rs.sendHf() > rw.sendHf(), rs + " vs " + rw);
    c("stone hall: decay in a sensible range", rs.decayTime() > 0.6 && rs.decayTime() < 4.0, rs.toString());
    var rout = reverb(stone, new V3(8.5, 2.5, 0.8), front, new V3(8.5, 1.7, 30));
    c("listener outside the hall hears less reverb", rout.sendGain() < rs.sendGain() * 0.5, rout.sendGain() + " vs " + rs.sendGain());
    c("open air has less reverb than a hall", r1.sendGain() < rs.sendGain() * 0.4, r1.sendGain() + " vs " + rs.sendGain());
    // 5. everything is finite and in range
    for (var r : List.of(rs, rw, rout, r1)) c("reverb values in range", r.sendGain() >= 0 && r.sendGain() <= 1 && r.sendHf() >= 0.3 && r.sendHf() <= 1 && Float.isFinite(r.decayTime()), r.toString());
    // 6. the screen's own front: a listener right behind a thick wall -> filter never goes to zero
    Grid thick = new Grid(); thick.box(-20, 0, 2, 20, 10, 12, 1.0, 0.9);
    var d6 = direct(thick, origin, listener);
    c("thick wall: filter floor", d6.hfL() >= 0.0199 && d6.gainL() > 0.3, d6.toString());
    // 6b. the material of the wall must be clearly audible: one block of each, same geometry
    double[][] mats = {{0.12, 0.7}, {0.55, 0.45}, {1.0, 0.92}, {1.8, 0.05}};   // glass, wood, stone, wool
    String[] names = {"glass", "wood", "stone", "wool"};
    float[] gains = new float[4], hfs = new float[4];
    for (int i = 0; i < 4; i++) {
      Grid one = new Grid(); one.box(-40, -1, -40, 40, -1, 40, 1.0, 0.3); one.box(-20, 0, 4, 20, 10, 4, mats[i][0], mats[i][1]);
      var d = direct(one, origin, listener); gains[i] = d.gainL(); hfs[i] = d.hfL();
    }
    System.out.println("     one block: " + java.util.Arrays.toString(names) + " gain " + java.util.Arrays.toString(gains) + " hf " + java.util.Arrays.toString(hfs));
    boolean ordered = true;
    for (int i = 0; i < 3; i++) ordered &= gains[i] - gains[i + 1] >= 0.08f && hfs[i] > hfs[i + 1];
    c("materials: glass > wood > stone > wool, gain steps >= 0.08", ordered, "");
    c("materials: glass is nearly clear, stone clearly muffled", hfs[0] > 0.7 && hfs[2] < 0.3 && hfs[0] / hfs[2] > 3, "");
    // 7. result record validity
    var res = new AcousticResult(d2.gainL(), d2.hfL(), d2.gainR(), d2.hfR(), rs.sendGain(), rs.sendHf(), rs.decayTime());
    c("AcousticResult valid", res.isValid() && AcousticResult.OPEN.isValid() && !new AcousticResult(Float.NaN, 1, 1, 1, 0, 1, 1).isValid(), "");
    System.out.println(fail == 0 ? "ALL PASS" : fail + " FAILED");
  }
}
