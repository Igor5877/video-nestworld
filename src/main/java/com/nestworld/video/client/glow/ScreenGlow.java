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
 * Colour light from a playing screen, spread like block light: it floods through the air from the screen with one level
 * lost per block (round corners, into every corner of the room) and lights every exposed block face it touches, floor,
 * walls and ceiling alike, with smooth per-corner values. The colour at each spot is the colour of the part of the
 * picture the light came from (a grid of 8x5 to 32x18 cells), blurrier the farther it travelled. Drawn as soft additive
 * overlays on the block faces; the lit faces are worked out about once a second, so per frame only their colours change.
 * The block light of a playing screen (a fixed level from the server) stays as it is.
 */
public final class ScreenGlow {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Quality 1..3: how many blocks the light travels, how many faces at most, and a limit on the cells searched. */
    private static final int[] REACH = {6, 10, 15};
    private static final int[] MAX_FACES = {400, 1200, 3000};
    private static final int[] MAX_CELLS = {20_000, 45_000, 90_000};
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
                state.faces = buildFaces(level, screen, screen(screen), quality);
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

    private List<Face> buildFaces(Level level, VideoScreenBlockEntity screen, Geometry g, int quality) {
        final int reach = REACH[quality - 1], maxCells = MAX_CELLS[quality - 1];
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        // The region searched: the screen and its surroundings within reach, limited to the part near the viewer.
        int x0 = (int) Math.floor(Math.max(g.bounds.minX - reach, camera.x - 24)), x1 = (int) Math.ceil(Math.min(g.bounds.maxX + reach, camera.x + 24));
        int y0 = (int) Math.floor(Math.max(g.bounds.minY - reach, camera.y - 16)), y1 = (int) Math.ceil(Math.min(g.bounds.maxY + reach, camera.y + 16));
        int z0 = (int) Math.floor(Math.max(g.bounds.minZ - reach, camera.z - 24)), z1 = (int) Math.ceil(Math.min(g.bounds.maxZ + reach, camera.z + 24));
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz > 400_000L) return List.of();
        int total = sx * sy * sz;
        byte[] kind = new byte[total];        // 0 unknown, 1 passable air, 2 solid or blocked
        byte[] light = new byte[total];       // light level 0..reach
        short[] srcU = new short[total], srcV = new short[total];   // where in the picture the light came from (0..1000)
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        java.util.function.IntUnaryOperator classify = idx -> {
            int x = x0 + idx / (sy * sz), y = y0 + (idx / sz) % sy, z = z0 + idx % sz;
            pos.set(x, y, z);
            if (!level.isLoaded(pos)) return 2;
            BlockState st = level.getBlockState(pos);
            if (st.getBlock() instanceof VideoScreenBlock) return 2;
            return st.isSolidRender(level, pos) ? 2 : 1;
        };
        // Start cells: the air right in front of every tile of the screen, each remembering which part of the picture it is.
        Direction facing = screen.getBlockState().getValue(VideoScreenBlock.FACING), right = facing.getCounterClockWise();
        int cols = screen.getScreenCols(), rows = screen.getScreenRows();
        int[] queue = new int[Math.min(total, maxCells)];
        int head = 0, tail = 0;
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            BlockPos front = screen.getBlockPos().relative(right, c).above(r).relative(facing);
            int px = front.getX() - x0, py = front.getY() - y0, pz = front.getZ() - z0;
            if (px < 0 || py < 0 || pz < 0 || px >= sx || py >= sy || pz >= sz) continue;
            int idx = (px * sy + py) * sz + pz;
            if (kind[idx] == 0) kind[idx] = (byte) classify.applyAsInt(idx);
            if (kind[idx] != 1 || light[idx] != 0 || tail >= queue.length) continue;
            light[idx] = (byte) reach;
            srcU[idx] = (short) Math.round(1000.0 * (c + 0.5) / cols);
            srcV[idx] = (short) Math.round(1000.0 * (1.0 - (r + 0.5) / rows));
            queue[tail++] = idx;
        }
        // Flood outwards, one level lost per block, like the game's own block light.
        final int[] dx = {1, -1, 0, 0, 0, 0}, dy = {0, 0, 1, -1, 0, 0}, dz = {0, 0, 0, 0, 1, -1};
        int visited = tail;
        while (head < tail) {
            int idx = queue[head++];
            int lv = light[idx];
            if (lv <= 1) continue;
            int x = idx / (sy * sz), y = (idx / sz) % sy, z = idx % sz;
            for (int k = 0; k < 6; k++) {
                int nx = x + dx[k], ny = y + dy[k], nz = z + dz[k];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) continue;
                int n = (nx * sy + ny) * sz + nz;
                if (kind[n] == 0) kind[n] = (byte) classify.applyAsInt(n);
                if (kind[n] != 1 || light[n] != 0 || visited >= maxCells || tail >= queue.length) continue;
                light[n] = (byte) (lv - 1); srcU[n] = srcU[idx]; srcV[n] = srcV[idx];
                queue[tail++] = n; visited++;
            }
        }
        // Every exposed block face that touches lit air gets light, smoothed over the cells around each of its corners.
        List<Face> found = new ArrayList<>();
        double gridCellWidth = g.width / (double) GRID_COLS[quality - 1];   // blocks per grid cell on the screen
        for (int q = 0; q < tail; q++) {
            int idx = queue[q];
            int x = idx / (sy * sz), y = (idx / sz) % sy, z = idx % sz;
            for (int k = 0; k < 6; k++) {
                int nx = x + dx[k], ny = y + dy[k], nz = z + dz[k];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) continue;
                int n = (nx * sy + ny) * sz + nz;
                if (kind[n] == 0) kind[n] = (byte) classify.applyAsInt(n);
                if (kind[n] != 2) continue;
                // The face of the solid cell n that looks back into the lit cell idx.
                Face f = faceInto(g, x0 + x, y0 + y, z0 + z, k, kind, light, srcU, srcV, x, y, z, sx, sy, sz, reach, gridCellWidth);
                if (f != null) found.add(f);
            }
        }
        found.sort(Comparator.comparingDouble((Face f) -> -(f.corner[0] + f.corner[1] + f.corner[2] + f.corner[3])));
        int max = MAX_FACES[quality - 1];
        return found.size() > max ? new ArrayList<>(found.subList(0, max)) : found;
    }

    /** Builds the face of the block next to the lit cell (x, y, z) in direction {@code dir}: smooth corner values and colours. */
    private static Face faceInto(Geometry g, int wx, int wy, int wz, int dir, byte[] kind, byte[] light, short[] srcU, short[] srcV,
                                 int x, int y, int z, int sx, int sy, int sz, int reach, double gridCellWidth) {
        final int[] dxs = {1, -1, 0, 0, 0, 0}, dys = {0, 0, 1, -1, 0, 0}, dzs = {0, 0, 0, 0, 1, -1};
        // The cell is lit air; the solid block is at +dir. Keep the decal on the shared boundary.
        double fx = wx + 0.5 + dxs[dir] * 0.5, fy = wy + 0.5 + dys[dir] * 0.5, fz = wz + 0.5 + dzs[dir] * 0.5;
        int[] t1 = dxs[dir] != 0 ? new int[]{0, 1, 0} : new int[]{1, 0, 0};
        int[] t2 = dzs[dir] != 0 ? new int[]{0, 1, 0} : new int[]{0, 0, 1};
        if (dys[dir] != 0) { t1 = new int[]{1, 0, 0}; t2 = new int[]{0, 0, 1}; }
        Face f = new Face();
        f.x = fx; f.y = fy; f.z = fz; f.nx = -dxs[dir]; f.ny = -dys[dir]; f.nz = -dzs[dir];
        double[][] signs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        V3 toScreen = g.centre.sub(new V3(fx, fy, fz));
        V3 l = toScreen.length() < 1e-4 ? g.front : toScreen.normalized();
        double facingScreen = Math.max(0.0, new V3(f.nx, f.ny, f.nz).dot(l));
        float shade = (float) (0.55 + 0.45 * facingScreen);         // a face turned towards the screen is a little brighter
        for (int i = 0; i < 4; i++) {
            int s1 = (int) signs[i][0], s2 = (int) signs[i][1];
            double sum = 0; int count = 0, bestLevel = -1; int bestU = 500, bestV = 500;
            for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
                int cx = x + (a == 1 ? s1 * t1[0] : 0) + (b == 1 ? s2 * t2[0] : 0);
                int cy = y + (a == 1 ? s1 * t1[1] : 0) + (b == 1 ? s2 * t2[1] : 0);
                int cz = z + (a == 1 ? s1 * t1[2] : 0) + (b == 1 ? s2 * t2[2] : 0);
                if (cx < 0 || cy < 0 || cz < 0 || cx >= sx || cy >= sy || cz >= sz) continue;
                int idx = (cx * sy + cy) * sz + cz;
                if (kind[idx] != 1) continue;                      // solid cells do not count, like smooth lighting
                sum += light[idx]; count++;
                if (light[idx] > bestLevel) { bestLevel = light[idx]; bestU = srcU[idx]; bestV = srcV[idx]; }
            }
            double average = count == 0 ? 0 : sum / count;
            f.corner[i] = (float) (Math.max(0.0, average) / reach * shade);
            f.cornerPos[i] = GlowFaceGeometry.corner(wx, wy, wz, dir, i);
            f.u[i] = bestU / 1000f; f.v[i] = bestV / 1000f;
            // The farther the light travelled, the blurrier its colour.
            double travelled = Math.max(1, reach - Math.max(0, bestLevel));
            // A display is an area emitter, not a projector. Sample at least a
            // block-wide footprint, widening as the light travels through the room.
            f.level[i] = (float) Math.max(0.0, Math.log(Math.max(1.0, travelled / gridCellWidth)) / Math.log(2.0));
        }
        float strongest = Math.max(Math.max(f.corner[0], f.corner[1]), Math.max(f.corner[2], f.corner[3]));
        return strongest <= 0.02f ? null : f;
    }

    /** Draws every active glow; called after translucent blocks. {@code cam} = camera position. */
    private long lastRenderLog;

    public void render(PoseStack poseStack, Vec3 cam) {
        if (NestworldConfig.DEBUG_STATS.get() && System.nanoTime() - lastRenderLog > 2_000_000_000L) {
            lastRenderLog = System.nanoTime();
            LOGGER.info("Nestworld Video glow render: {} screens, strength {}", states.size(), NestworldConfig.SCREEN_GLOW_STRENGTH.get());
        }
        if (!enabled() || states.isEmpty()) return;
        float strength = NestworldConfig.SCREEN_GLOW_STRENGTH.get() / 100f;
        if (strength <= 0f) return;
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = null;
        Matrix4f matrix = poseStack.last().pose();
        for (State state : states.values()) {
            if (!state.active || state.faces.isEmpty()) continue;
            if (state.mips.length == 0) continue;
            if (consumer == null) consumer = buffers.getBuffer(GlowRenderType.SURFACE);
            float[] colour = new float[3];
            for (Face f : state.faces) {
                // Only the visible side receives a decal. NO_CULL handles the different
                // face windings, so each surface is submitted once, never additively twice.
                if ((cam.x - f.x) * f.nx + (cam.y - f.y) * f.ny + (cam.z - f.z) * f.nz <= 0) continue;
                for (int i = 0; i < 4; i++) {
                    sample(state, f.u[i], f.v[i], f.level[i], colour);
                    float a = Math.min(1f, f.corner[i] * strength * 0.6f);
                    consumer.vertex(matrix, (float) (f.cornerPos[i][0] - cam.x), (float) (f.cornerPos[i][1] - cam.y), (float) (f.cornerPos[i][2] - cam.z))
                            .color(colour[0], colour[1], colour[2], a).uv(0.5f, 0.5f).endVertex();
                }
            }
        }
        if (consumer != null) buffers.endBatch(GlowRenderType.SURFACE);
    }

    /** Mip chain of the smoothed grid: each level averages 2x2 cells of the one before. */
    private static void buildMips(State state) {
        int levels = 1, c = state.cols, r = state.rows;
        while ((c > 1 || r > 1) && levels < 6) { c = (c + 1) / 2; r = (r + 1) / 2; levels++; }
        if (state.mips.length != levels) { state.mips = new float[levels][]; state.mipCols = new int[levels]; state.mipRows = new int[levels]; }
        if (state.mips[0] == null || state.mips[0].length != state.current.length)
            state.mips[0] = new float[state.current.length];
        // Average light, not gamma-encoded video values. Convert back only when
        // submitting the vertex colour to Minecraft's shader pipeline.
        for (int i = 0; i < state.current.length; i++)
            state.mips[0][i] = (float) Math.pow(Math.max(0f, Math.min(1f, state.current[i])), 2.2);
        state.mipCols[0] = state.cols; state.mipRows[0] = state.rows;
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

    /** Broad colour spill, with a diffuse contribution from the rest of the display. */
    private static void sample(State state, float u, float v, float level, float[] out) {
        out[0] = out[1] = out[2] = 0f;
        float l = Math.max(0f, Math.min(state.mips.length - 1f, level));
        int l0 = (int) l, l1 = Math.min(state.mips.length - 1, l0 + 1);
        float k = l - l0;
        sampleLevel(state, l0, u, v, out, 0.65f * (1 - k));
        if (k > 0f) sampleLevel(state, l1, u, v, out, 0.65f * k);
        sampleLevel(state, state.mips.length - 1, 0.5f, 0.5f, out, 0.35f);
        for (int ch = 0; ch < 3; ch++)
            out[ch] = (float) Math.pow(Math.max(0f, Math.min(1f, out[ch])), 1.0 / 2.2);
    }
}
