package com.nestworld.video.client.glow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.block.VideoScreenBlock;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.client.acoustics.MinecraftRayWorld;
import com.nestworld.video.client.acoustics.V3;
import com.nestworld.video.client.util.Display;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Colour light from a playing screen onto the surfaces around it that face it (floor, walls, ceiling), following what the
 * picture shows: the frame is reduced to a grid of colours (8x5 to 32x18 by quality) and every corner of every lit face
 * takes the colour of the part of the picture it "sees"; the farther from the screen, the coarser (a blurrier average),
 * like real light spreading. Drawn as soft additive overlays on the exposed block faces; the faces are found once a
 * second, so per frame only their colours change.
 * The block light of a playing screen (a fixed level from the server) stays as it is.
 */
public final class ScreenGlow {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Quality 1..3: reach in blocks and how many faces at most. */
    private static final double[] REACH = {6.0, 10.0, 15.0};
    private static final int[] MAX_FACES = {250, 700, 1500};
    private static final int REBUILD_TICKS = 20;
    /** Columns of the colour grid per quality level (must match the display's sampling). */
    private static final int[] GRID_COLS = {8, 16, 32};

    private static final class Face {
        double x, y, z;            // centre of the face
        int nx, ny, nz;            // outward normal
        float[] corner = new float[4];   // light weight at each corner (0..1)
        double[][] cornerPos = new double[4][3];
        float[] u = new float[4], v = new float[4], level = new float[4];   // where in the picture each corner looks, and how coarsely
    }

    private static final class State {
        long lastBuild = Long.MIN_VALUE / 2;
        List<Face> faces = List.of();
        float[] current;          // smoothed colours of the grid cells (r,g,b each)
        int cols, rows;
        float[][] mips = new float[0][];   // mip chain: level 0 = the grid, each next one half the size
        int[] mipCols = new int[0], mipRows = new int[0];
        boolean active;
    }

    private final Map<BlockPos, State> states = new HashMap<>();

    /**
     * Coloured light is only drawn under a shader pack (Oculus/Iris): the vanilla lighting has no coloured light and flat
     * additive patches on the floor look out of place there. {@code nestworldvideo.glowforce} is for tests.
     */
    public static boolean enabled() {
        return NestworldConfig.SCREEN_GLOW.get() > 0
                && (com.nestworld.video.client.VideoRenderTypes.shaderPackActive() || Boolean.getBoolean("nestworldvideo.glowforce"));
    }

    /** Called every tick for each screen that is playing close to the player. */
    public void update(Level level, BlockPos pos, VideoScreenBlockEntity screen, Display display, long gameTime) {
        int quality = NestworldConfig.SCREEN_GLOW.get();
        if (!enabled()) { states.remove(pos); return; }
        GlowGrid target = display.glowGrid();
        State state = states.computeIfAbsent(pos, p -> new State());
        if (target == null || !target.isValid()) { state.active = false; return; }
        state.active = true;
        if (state.current == null || state.cols != target.cols() || state.rows != target.rows()) {
            state.cols = target.cols(); state.rows = target.rows(); state.current = target.rgb().clone();
        } else {
            for (int i = 0; i < state.current.length; i++) state.current[i] += (target.rgb()[i] - state.current[i]) * 0.35f;   // about 100 ms
        }
        buildMips(state);
        if (gameTime - state.lastBuild >= REBUILD_TICKS) {
            state.lastBuild = gameTime;
            try {
                state.faces = buildFaces(level, screen(screen), quality);
                if (NestworldConfig.DEBUG_STATS.get()) LOGGER.info("Nestworld Video glow: {} surfaces lit around the screen at {} (strongest {}, average {}), colour grid {}x{}", state.faces.size(), pos,
                        state.faces.isEmpty() ? 0 : String.format("%.2f", state.faces.get(0).corner[0]),
                        String.format("%.3f", state.faces.stream().mapToDouble(f -> (f.corner[0] + f.corner[1] + f.corner[2] + f.corner[3]) / 4).average().orElse(0)),
                        state.cols, state.rows);
            }
            catch (RuntimeException e) { state.faces = List.of(); LOGGER.warn("Nestworld Video: screen glow could not be built: {}", e.toString()); }
        }
    }

    public void forget(BlockPos pos) { states.remove(pos); }
    public void clear() { states.clear(); }

    private record Geometry(V3 centre, V3 front, V3 right, double width, double height, AABB bounds) {}

    private static Geometry screen(VideoScreenBlockEntity screen) {
        Direction facing = screen.getBlockState().getValue(VideoScreenBlock.FACING);
        Direction right = facing.getCounterClockWise();
        AABB b = screen.getRenderBoundingBox();
        return new Geometry(new V3((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, (b.minZ + b.maxZ) / 2),
                new V3(facing.getStepX(), 0, facing.getStepZ()), new V3(right.getStepX(), 0, right.getStepZ()),
                screen.getScreenCols(), screen.getScreenRows(), b);
    }

    private List<Face> buildFaces(Level level, Geometry g, int quality) {
        double reach = REACH[quality - 1];
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        // Only the part of the room near the viewer: a huge wall would otherwise mean a huge scan.
        int x0 = (int) Math.floor(Math.max(g.bounds.minX - reach, camera.x - 24)), x1 = (int) Math.ceil(Math.min(g.bounds.maxX + reach, camera.x + 24));
        int y0 = (int) Math.floor(Math.max(g.bounds.minY - reach, camera.y - 16)), y1 = (int) Math.ceil(Math.min(g.bounds.maxY + reach, camera.y + 16));
        int z0 = (int) Math.floor(Math.max(g.bounds.minZ - reach, camera.z - 24)), z1 = (int) Math.ceil(Math.min(g.bounds.maxZ + reach, camera.z + 24));
        MinecraftRayWorld world = new MinecraftRayWorld(level);
        List<Face> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), neighbour = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            pos.set(x, y, z);
            if (!level.isLoaded(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.isSolidRender(level, pos)) continue;
            for (Direction dir : Direction.values()) {
                neighbour.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                if (!level.isLoaded(neighbour) || level.getBlockState(neighbour).isSolidRender(level, neighbour)) continue;
                Face face = faceFor(world, g, x, y, z, dir, reach, GRID_COLS[quality - 1]);
                if (face != null) found.add(face);
            }
        }
        found.sort(Comparator.comparingDouble((Face f) -> -(f.corner[0] + f.corner[1] + f.corner[2] + f.corner[3])));
        int max = MAX_FACES[quality - 1];
        return found.size() > max ? new ArrayList<>(found.subList(0, max)) : found;
    }

    /** Nearest point of the screen's surface, offset a little in front of it. */
    private static V3 nearestOnScreen(Geometry g, V3 p) {
        V3 d = p.sub(g.centre);
        double u = Math.max(-g.width / 2, Math.min(g.width / 2, d.dot(g.right)));
        double v = Math.max(-g.height / 2, Math.min(g.height / 2, d.y()));
        return g.centre.add(g.right.scale(u)).add(new V3(0, v, 0)).add(g.front.scale(0.5));
    }

    private static float weightAt(Geometry g, V3 p, V3 normal, double reach) {
        V3 s = nearestOnScreen(g, p);
        double dist = s.sub(p).length();
        if (dist >= reach || dist < 1e-4) return dist < 1e-4 ? 1f : 0f;
        // The screen is an area light: take the direction to its middle height, or a floor right under the bottom edge
        // (and a ceiling above the top edge) would only ever see grazing light.
        V3 toScreen = new V3(s.x(), g.centre.y(), s.z()).sub(p);
        double directionLength = toScreen.length();
        V3 l = directionLength < 1e-4 ? g.front : toScreen.scale(1 / directionLength);
        double facingScreen = normal.dot(l);                  // the surface faces the screen
        double screenFacing = g.front.dot(l.scale(-1));       // the screen faces the surface
        if (facingScreen <= 0.02 || screenFacing <= 0.0) return 0f;
        // Falls off gently, like block light (a torch reaches far), not like an inverse-square spotlight.
        double falloff = Math.pow(1 - dist / reach, 1.3);
        return (float) (falloff * Math.min(1.0, facingScreen * 1.5) * Math.sqrt(screenFacing));
    }

    private static Face faceFor(MinecraftRayWorld world, Geometry g, int x, int y, int z, Direction dir, double reach, int gridCols) {
        V3 normal = new V3(dir.getStepX(), dir.getStepY(), dir.getStepZ());
        V3 centre = new V3(x + 0.5 + dir.getStepX() * 0.5, y + 0.5 + dir.getStepY() * 0.5, z + 0.5 + dir.getStepZ() * 0.5);
        if (g.front.dot(centre.sub(g.centre)) < 0.45) return null;        // behind or beside the screen's surface plane
        float wc = weightAt(g, centre, normal, reach);
        if (wc <= 0.01f) return null;
        // Light must actually reach it: nothing solid between the screen and the face.
        V3 s = nearestOnScreen(g, centre).add(g.front.scale(0.05));
        if (world.occlusion(s, centre.add(normal.scale(0.05)), 0.5) > 0.3) return null;
        Face f = new Face();
        f.x = centre.x(); f.y = centre.y(); f.z = centre.z(); f.nx = dir.getStepX(); f.ny = dir.getStepY(); f.nz = dir.getStepZ();
        // Corners of the 1x1 face, pushed a hair off the surface; the weight is evaluated at each corner for a smooth gradient.
        V3 t1 = dir.getAxis() == Direction.Axis.Y ? new V3(1, 0, 0) : new V3(0, 1, 0);
        V3 t2 = dir.getAxis() == Direction.Axis.X ? new V3(0, 0, 1) : dir.getAxis() == Direction.Axis.Z ? new V3(1, 0, 0) : new V3(0, 0, 1);
        double[][] signs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            V3 c = centre.add(t1.scale(0.5 * signs[i][0])).add(t2.scale(0.5 * signs[i][1])).add(normal.scale(0.02));
            f.cornerPos[i][0] = c.x(); f.cornerPos[i][1] = c.y(); f.cornerPos[i][2] = c.z();
            f.corner[i] = Math.max(weightAt(g, c, normal, reach), wc * 0.35f);
            // Which part of the picture this corner looks at, and how far it is (the farther, the blurrier the colour).
            V3 d = c.sub(g.centre);
            f.u[i] = (float) Math.max(0, Math.min(1, d.dot(g.right) / g.width + 0.5));
            f.v[i] = (float) Math.max(0, Math.min(1, 0.5 - d.y() / g.height));    // row 0 is the top of the picture
            f.level[i] = (float) Math.max(0.0, Math.log(Math.max(1.0, nearestOnScreen(g, c).sub(c).length() / (g.width / gridCols))) / Math.log(2.0));
        }
        return f;
    }

    /** Draws every active glow; called after translucent blocks. {@code cam} = camera position. */
    private long lastRenderLog;

    public void render(PoseStack poseStack, Vec3 cam) {
        if (NestworldConfig.DEBUG_STATS.get() && System.nanoTime() - lastRenderLog > 2_000_000_000L) {
            lastRenderLog = System.nanoTime();
            LOGGER.info("Nestworld Video glow render: {} screens, strength {}", states.size(), NestworldConfig.SCREEN_GLOW_STRENGTH.get());
        }
        if (states.isEmpty()) return;
        float strength = NestworldConfig.SCREEN_GLOW_STRENGTH.get() / 100f;
        if (strength <= 0f) return;
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = null;
        Matrix4f matrix = poseStack.last().pose();
        for (State state : states.values()) {
            if (!state.active || state.faces.isEmpty()) continue;
            if (state.mips.length == 0) continue;
            if (consumer == null) consumer = buffers.getBuffer(RenderType.lightning());
            float[] colour = new float[3];
            for (Face f : state.faces) {
                // The render type culls back faces and the winding depends on the face's side, so every quad is drawn both ways round.
                for (int pass = 0; pass < 2; pass++) for (int k = 0; k < 4; k++) {
                    int i = pass == 0 ? k : 3 - k;
                    sample(state, f.u[i], f.v[i], f.level[i], colour);
                    float a = Math.min(1f, f.corner[i] * strength * 1.2f);
                    consumer.vertex(matrix, (float) (f.cornerPos[i][0] - cam.x), (float) (f.cornerPos[i][1] - cam.y), (float) (f.cornerPos[i][2] - cam.z))
                            .color(colour[0], colour[1], colour[2], a).endVertex();
                }
            }
        }
        if (consumer != null) buffers.endBatch(RenderType.lightning());
    }

    /** Mip chain of the smoothed grid: each level averages 2x2 cells of the one before. */
    private static void buildMips(State state) {
        int levels = 1, c = state.cols, r = state.rows;
        while ((c > 1 || r > 1) && levels < 6) { c = (c + 1) / 2; r = (r + 1) / 2; levels++; }
        if (state.mips.length != levels) { state.mips = new float[levels][]; state.mipCols = new int[levels]; state.mipRows = new int[levels]; }
        state.mips[0] = state.current; state.mipCols[0] = state.cols; state.mipRows[0] = state.rows;
        for (int l = 1; l < levels; l++) {
            int pc = state.mipCols[l - 1], pr = state.mipRows[l - 1], nc = (pc + 1) / 2, nr = (pr + 1) / 2;
            float[] prev = state.mips[l - 1], next = state.mips[l] != null && state.mips[l].length == nc * nr * 3 ? state.mips[l] : new float[nc * nr * 3];
            for (int y = 0; y < nr; y++) for (int x = 0; x < nc; x++) for (int ch = 0; ch < 3; ch++) {
                float sum = 0; int n = 0;
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                    int px = x * 2 + dx, py = y * 2 + dy;
                    if (px < pc && py < pr) { sum += prev[(py * pc + px) * 3 + ch]; n++; }
                }
                next[(y * nc + x) * 3 + ch] = sum / Math.max(1, n);
            }
            state.mips[l] = next; state.mipCols[l] = nc; state.mipRows[l] = nr;
        }
    }

    /** Bilinear sample of one mip level at (u, v) in 0..1 (v from the top). */
    private static void sampleLevel(State state, int level, float u, float v, float[] out, float weight) {
        int c = state.mipCols[level], r = state.mipRows[level];
        float[] data = state.mips[level];
        float x = Math.max(0f, Math.min(c - 1f, u * c - 0.5f)), y = Math.max(0f, Math.min(r - 1f, v * r - 0.5f));
        int x0 = (int) x, y0 = (int) y, x1 = Math.min(c - 1, x0 + 1), y1 = Math.min(r - 1, y0 + 1);
        float fx = x - x0, fy = y - y0;
        for (int ch = 0; ch < 3; ch++) {
            float top = data[(y0 * c + x0) * 3 + ch] * (1 - fx) + data[(y0 * c + x1) * 3 + ch] * fx;
            float bottom = data[(y1 * c + x0) * 3 + ch] * (1 - fx) + data[(y1 * c + x1) * 3 + ch] * fx;
            out[ch] += (top * (1 - fy) + bottom * fy) * weight;
        }
    }

    /** Colour at a spot of the picture seen from a given distance (level = blur), a little more saturated than the average. */
    private static void sample(State state, float u, float v, float level, float[] out) {
        out[0] = out[1] = out[2] = 0f;
        float l = Math.max(0f, Math.min(state.mips.length - 1f, level));
        int l0 = (int) l, l1 = Math.min(state.mips.length - 1, l0 + 1);
        float k = l - l0;
        sampleLevel(state, l0, u, v, out, 1 - k);
        if (k > 0f) sampleLevel(state, l1, u, v, out, k);
        float lum = 0.2126f * out[0] + 0.7152f * out[1] + 0.0722f * out[2];
        for (int ch = 0; ch < 3; ch++) out[ch] = Math.max(0f, Math.min(1f, lum + (out[ch] - lum) * 1.5f));
    }
}
