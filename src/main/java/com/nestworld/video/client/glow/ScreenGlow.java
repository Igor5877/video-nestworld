package com.nestworld.video.client.glow;

import com.mojang.blaze3d.platform.NativeImage;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.block.VideoScreenBlock;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.client.util.Display;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Colour light from a playing screen under the patched BSL shader pack. The mod draws nothing itself: every frame it
 * writes the nearest screens into a small texture that the pack reads as {@code customTexture.nwScreenLight}, and the
 * pack lights each surface from the screen as from a glowing rectangle (colour of each part of the picture, angle and
 * distance). Walls are respected through the screen's own block light ({@code LIT}).
 *
 * <p>Layout, one row per screen (RGBA8, read with texelFetch): texel 0 = (normal x + 1, normal z + 1, 0, 255 if the row
 * is used); texel 1 = centre x (RG) and y (BA), texel 2 = centre z (RG), width and height in blocks (B, A); texels
 * 3.. = the picture as {@value #COLS} x {@value #ROWS} sRGB cells, row 0 at the top. Positions are relative to the camera
 * of the frame, 16 bits each: {@code (value + 256) * 128}.
 */
public final class ScreenGlow {
    public static final ResourceLocation TEXTURE = new ResourceLocation("nestworldvideo", "dynamic/screen_light");
    static final int COLS = 8, ROWS = 5, MAX_SCREENS = 4, HEADER = 3;
    private static final int WIDTH = HEADER + COLS * ROWS;

    private static final class State {
        final float[] rgb = new float[COLS * ROWS * 3];   // smoothed sRGB colours
        boolean init;
        Vec3 centre = Vec3.ZERO;
        Direction facing = Direction.NORTH;
        int width, height;
        long lastSeen;
    }

    private final Map<BlockPos, State> states = new HashMap<>();
    private DynamicTexture texture;
    private boolean uploadedEmpty;

    /** Only under a shader pack (Oculus/Iris): the vanilla pipeline does not read the texture. */
    public static boolean enabled() {
        return NestworldConfig.SCREEN_GLOW.get() > 0
                && (com.nestworld.video.client.VideoRenderTypes.shaderPackActive() || Boolean.getBoolean("nestworldvideo.glowforce"));
    }

    /** Called every tick for each screen that shows a picture close to the player (also while paused on a frame). */
    public void update(Level level, BlockPos pos, VideoScreenBlockEntity screen, Display display, long gameTime) {
        if (!enabled()) { states.clear(); return; }
        GlowGrid grid = display.glowGrid();
        if (grid == null || !grid.isValid()) return;
        State state = states.computeIfAbsent(pos, p -> new State());
        Direction facing = screen.getBlockState().getValue(VideoScreenBlock.FACING);
        AABB b = screen.getRenderBoundingBox();
        // The picture is the front face of the screen blocks.
        double front = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? b.max(facing.getAxis()) : b.min(facing.getAxis());
        double cx = (b.minX + b.maxX) / 2, cy = (b.minY + b.maxY) / 2, cz = (b.minZ + b.maxZ) / 2;
        if (facing.getAxis() == Direction.Axis.X) cx = front; else cz = front;
        state.centre = new Vec3(cx, cy, cz);
        state.facing = facing;
        state.width = screen.getScreenCols();
        state.height = screen.getScreenRows();
        state.lastSeen = gameTime;
        float k = state.init ? 0.3f : 1f;   // about 150 ms to follow the picture: smooth, never a jump
        for (int row = 0; row < ROWS; row++) for (int col = 0; col < COLS; col++) {
            float[] c = cell(grid, col, row);
            int o = (row * COLS + col) * 3;
            for (int ch = 0; ch < 3; ch++) state.rgb[o + ch] += (c[ch] - state.rgb[o + ch]) * k;
        }
        state.init = true;
    }

    public void forget(BlockPos pos) { states.remove(pos); }
    public void clear() { states.clear(); }

    /** Writes the screens nearest to the camera into the texture; once per frame before the world is drawn. */
    public void upload(Vec3 camera) {
        Minecraft mc = Minecraft.getInstance();
        if (texture == null) {
            texture = new DynamicTexture(WIDTH, MAX_SCREENS, true);
            mc.getTextureManager().register(TEXTURE, texture);
        }
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        states.values().removeIf(s -> now - s.lastSeen > 40 || now < s.lastSeen);   // a screen that went out of range or stopped
        if (states.isEmpty() || !enabled()) {
            if (uploadedEmpty) return;
            texture.getPixels().fillRect(0, 0, WIDTH, MAX_SCREENS, 0);
            texture.upload();
            uploadedEmpty = true;
            return;
        }
        List<State> nearest = new ArrayList<>(states.values());
        nearest.sort(Comparator.comparingDouble(s -> s.centre.distanceToSqr(camera)));
        NativeImage pixels = texture.getPixels();
        pixels.fillRect(0, 0, WIDTH, MAX_SCREENS, 0);
        for (int row = 0; row < Math.min(MAX_SCREENS, nearest.size()); row++) {
            State s = nearest.get(row);
            Vec3 rel = s.centre.subtract(camera);
            if (Math.abs(rel.x) > 250 || Math.abs(rel.y) > 250 || Math.abs(rel.z) > 250) continue;
            int x = fixed(rel.x), y = fixed(rel.y), z = fixed(rel.z);
            put(pixels, 0, row, s.facing.getStepX() + 1, s.facing.getStepZ() + 1, 0, 255);
            put(pixels, 1, row, x >> 8, x & 255, y >> 8, y & 255);
            put(pixels, 2, row, z >> 8, z & 255, Math.min(255, s.width), Math.min(255, s.height));
            for (int i = 0; i < COLS * ROWS; i++)
                put(pixels, HEADER + i, row, byteOf(s.rgb[i * 3]), byteOf(s.rgb[i * 3 + 1]), byteOf(s.rgb[i * 3 + 2]), 255);
        }
        texture.upload();
        uploadedEmpty = false;
    }

    private static int fixed(double v) { return (int) Math.max(0, Math.min(65535, Math.round((v + 256) * 128))); }
    private static int byteOf(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }

    /** NativeImage stores ABGR. */
    private static void put(NativeImage image, int x, int y, int r, int g, int b, int a) {
        image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
    }

    /** Average sRGB colour (averaged as light) of the grid cells under one of the texture's cells. */
    private static float[] cell(GlowGrid g, int col, int row) {
        int x0 = col * g.cols() / COLS, x1 = Math.max(x0 + 1, (col + 1) * g.cols() / COLS);
        int y0 = row * g.rows() / ROWS, y1 = Math.max(y0 + 1, (row + 1) * g.rows() / ROWS);
        double r = 0, gr = 0, b = 0; int n = 0;
        for (int y = y0; y < Math.min(y1, g.rows()); y++) for (int x = x0; x < Math.min(x1, g.cols()); x++) {
            int o = (y * g.cols() + x) * 3;
            r += Math.pow(g.rgb()[o], 2.2); gr += Math.pow(g.rgb()[o + 1], 2.2); b += Math.pow(g.rgb()[o + 2], 2.2); n++;
        }
        if (n == 0) return new float[3];
        return new float[]{(float) Math.pow(r / n, 1 / 2.2), (float) Math.pow(gr / n, 1 / 2.2), (float) Math.pow(b / n, 1 / 2.2)};
    }
}
