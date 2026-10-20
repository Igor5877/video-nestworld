package com.nestworld.video.client.glow;

import com.mojang.logging.LogUtils;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.block.VideoScreenBlock;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.client.util.Display;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Colour light from a playing screen under a shader pack. The mod does not draw any light itself: every lit tile of the
 * screen carries the colour of its part of the picture in its block state ({@link VideoScreenBlock#GLOW}), the patched
 * BSL shader pack (coloured block light on) reads it as a light source and spreads it through the room with its own
 * walls, corners and doors. Only the client changes the state, a few times a second at most, so the section meshes are
 * rebuilt rarely.
 */
public final class ScreenGlow {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks between two looks at the picture. */
    private static final int UPDATE_TICKS = 4;
    /** A tile keeps a colour at least this long, so a flickering picture does not rebuild its chunk section all the time. */
    private static final int HOLD_TICKS = 10;
    /** Most tiles whose state changes in one update: a big wall changes over a few updates instead of at once. */
    private static final int MAX_CHANGES = 48;
    /** Linear light of the five levels of each channel; must match the table in the shader patch. */
    static final float[] LEVELS = {0f, 0.12f, 0.3f, 0.6f, 1f};

    private static final class Tile {
        float r, g, b;          // smoothed linear colour
        boolean init;
        long changedAt = Long.MIN_VALUE / 2;
    }

    private static final class State {
        long lastUpdate = Long.MIN_VALUE / 2;
        final Map<BlockPos, Tile> tiles = new HashMap<>();
        Direction facing = Direction.NORTH;
    }

    private final Map<BlockPos, State> states = new HashMap<>();

    /** Only under a shader pack (Oculus/Iris): without one there is no coloured light to read the state. */
    public static boolean enabled() {
        return NestworldConfig.SCREEN_GLOW.get() > 0
                && (com.nestworld.video.client.VideoRenderTypes.shaderPackActive() || Boolean.getBoolean("nestworldvideo.glowforce"));
    }

    /** Called every tick for each screen that is playing close to the player. */
    public void update(Level level, BlockPos pos, VideoScreenBlockEntity screen, Display display, long gameTime) {
        if (!enabled()) { forget(pos); return; }
        GlowGrid grid = display.glowGrid();
        if (grid == null || !grid.isValid()) return;
        State state = states.computeIfAbsent(pos, p -> new State());
        if (gameTime - state.lastUpdate < UPDATE_TICKS) return;
        state.lastUpdate = gameTime;
        Direction facing = screen.getBlockState().getValue(VideoScreenBlock.FACING);
        state.facing = facing;
        Direction right = facing.getCounterClockWise();
        int cols = screen.getScreenCols(), rows = screen.getScreenRows();
        // The server lights only an even grid of the tiles of a big wall; each lit tile speaks for the patch around it.
        int step = Math.max(1, VideoScreenBlockEntity.lightStep(cols * rows));
        float gain = NestworldConfig.SCREEN_GLOW_STRENGTH.get() / 100f * 2f;
        int changes = 0;
        for (int row = 0; row < rows; row++) for (int col = 0; col < cols; col++) {
            BlockPos tilePos = pos.relative(right, col).above(row);
            BlockState current = level.getBlockState(tilePos);
            if (!(current.getBlock() instanceof VideoScreenBlock) || !current.getValue(VideoScreenBlock.LIT)) continue;
            Tile tile = state.tiles.computeIfAbsent(tilePos, p -> new Tile());
            float[] linear = regionColour(grid, (col + 0.5 - step / 2.0) / cols, (col + 0.5 + step / 2.0) / cols,
                    1.0 - (row + 0.5 + step / 2.0) / rows, 1.0 - (row + 0.5 - step / 2.0) / rows);
            if (!tile.init) { tile.r = linear[0]; tile.g = linear[1]; tile.b = linear[2]; tile.init = true; }
            else { tile.r += (linear[0] - tile.r) * 0.5f; tile.g += (linear[1] - tile.g) * 0.5f; tile.b += (linear[2] - tile.b) * 0.5f; }
            int target = code(tile.r * gain, tile.g * gain, tile.b * gain);
            int shown = current.getValue(VideoScreenBlock.GLOW);
            if (target == shown || gameTime - tile.changedAt < HOLD_TICKS || changes >= MAX_CHANGES) continue;
            tile.changedAt = gameTime;
            changes++;
            level.setBlock(tilePos, current.setValue(VideoScreenBlock.GLOW, target), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        if (NestworldConfig.DEBUG_STATS.get() && (changes > 0 || gameTime % 100 < UPDATE_TICKS))
            LOGGER.info("Nestworld Video glow: screen at {}: {} lit tiles, {} changed colour", pos, state.tiles.size(), changes);
    }

    /** Back to no colour light: the tiles of this screen lose their colour (the server's own light state is untouched). */
    public void forget(BlockPos pos) {
        State state = states.remove(pos);
        if (state == null) return;
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        for (BlockPos tilePos : state.tiles.keySet()) {
            BlockState current = level.getBlockState(tilePos);
            if (current.getBlock() instanceof VideoScreenBlock && current.getValue(VideoScreenBlock.GLOW) != 0)
                level.setBlock(tilePos, current.setValue(VideoScreenBlock.GLOW, 0), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    public void clear() { states.clear(); }

    /** Average linear colour of the part [u0,u1] x [v0,v1] (0..1, v from the top) of the grid. */
    private static float[] regionColour(GlowGrid g, double u0, double u1, double v0, double v1) {
        double x0 = Math.max(0, u0) * g.cols(), x1 = Math.min(1, u1) * g.cols();
        double y0 = Math.max(0, v0) * g.rows(), y1 = Math.min(1, v1) * g.rows();
        int cx0 = (int) Math.floor(x0), cx1 = Math.max(cx0, (int) Math.ceil(x1) - 1);
        int cy0 = (int) Math.floor(y0), cy1 = Math.max(cy0, (int) Math.ceil(y1) - 1);
        double r = 0, gr = 0, b = 0; int n = 0;
        for (int y = Math.max(0, cy0); y <= Math.min(g.rows() - 1, cy1); y++)
            for (int x = Math.max(0, cx0); x <= Math.min(g.cols() - 1, cx1); x++) {
                int o = (y * g.cols() + x) * 3;
                r += Math.pow(g.rgb()[o], 2.2); gr += Math.pow(g.rgb()[o + 1], 2.2); b += Math.pow(g.rgb()[o + 2], 2.2); n++;
            }
        return n == 0 ? new float[]{0, 0, 0} : new float[]{(float) (r / n), (float) (gr / n), (float) (b / n)};
    }

    /** The glow value (0..124) whose levels come closest to the linear colour. */
    static int code(float r, float g, float b) { return level(r) * 25 + level(g) * 5 + level(b); }

    private static int level(float v) {
        v = Math.max(0f, Math.min(1f, v));
        int best = 0;
        for (int i = 1; i < LEVELS.length; i++) if (Math.abs(LEVELS[i] - v) < Math.abs(LEVELS[best] - v)) best = i;
        return best;
    }
}
