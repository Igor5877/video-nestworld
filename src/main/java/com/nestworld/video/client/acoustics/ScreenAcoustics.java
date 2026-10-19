package com.nestworld.video.client.acoustics;

import com.mojang.logging.LogUtils;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.block.VideoScreenBlock;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.client.util.Display;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Room acoustics of the playing screens. The screen is the sound source: its left and right speakers sit at the edges
 * (the same physical points the player's audio uses before softening), shifted a little in front of the surface so the
 * screen's own wall does not block them. Runs on the client thread (the only one that may read the world) and hands the
 * audio thread an immutable {@link AcousticResult}.
 */
public final class ScreenAcoustics {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The direct sound is re-evaluated every 2 ticks (100 ms), the reverb every 5 (250 ms). */
    private static final int DIRECT_TICKS = 2, REVERB_TICKS = 5;
    private static final int[] RAYS = {12, 20, 32}, BOUNCES = {2, 3, 4};

    private static final class State {
        long lastDirect = Long.MIN_VALUE / 2, lastReverb = Long.MIN_VALUE / 2;
        AcousticSolver.Direct direct = new AcousticSolver.Direct(1f, 1f, 1f, 1f);
        AcousticSolver.Reverb reverb = new AcousticSolver.Reverb(0f, 1f, 1f);
        boolean failed;
        long lastLog = Long.MIN_VALUE / 2;
        AcousticResult result;
    }

    private final Map<BlockPos, State> states = new HashMap<>();

    /** Called every tick for each playing screen that is close enough to be audible. */
    public void update(Level level, BlockPos pos, VideoScreenBlockEntity screen, Display display, long gameTime) {
        if (!NestworldConfig.ACOUSTICS.get()) { display.setAcoustics(null); states.remove(pos); return; }
        State state = states.computeIfAbsent(pos, p -> new State());
        if (state.failed) return;
        boolean directDue = gameTime - state.lastDirect >= DIRECT_TICKS, reverbDue = gameTime - state.lastReverb >= REVERB_TICKS;
        if (!directDue && !reverbDue) return;
        try {
            Direction facing = screen.getBlockState().getValue(VideoScreenBlock.FACING);
            Direction right = facing.getCounterClockWise();
            V3 front = new V3(facing.getStepX(), 0, facing.getStepZ()), rightV = new V3(right.getStepX(), 0, right.getStepZ());
            AABB bounds = screen.getRenderBoundingBox();
            V3 centre = new V3((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2, (bounds.minZ + bounds.maxZ) / 2);
            double half = Math.min(screen.getScreenCols() / 2.0, 6.0) * 0.85;
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            V3 listener = new V3(camera.x, camera.y, camera.z);
            MinecraftRayWorld world = new MinecraftRayWorld(level);
            if (directDue) {
                state.lastDirect = gameTime;
                state.direct = AcousticSolver.direct(world, probes(centre.sub(rightV.scale(half)), rightV, front, half),
                        probes(centre.add(rightV.scale(half)), rightV, front, half), listener);
            }
            if (reverbDue) {
                state.lastReverb = gameTime;
                int quality = Math.max(1, Math.min(3, NestworldConfig.ACOUSTICS_QUALITY.get())) - 1;
                state.reverb = AcousticSolver.reverb(world, centre.add(front.scale(0.8)), front, listener, RAYS[quality], BOUNCES[quality],
                        NestworldConfig.ACOUSTICS_REVERB.get() / 100.0, 48);
            }
            AcousticResult result = new AcousticResult(state.direct.gainL(), state.direct.hfL(), state.direct.gainR(), state.direct.hfR(),
                    state.reverb.sendGain(), state.reverb.sendHf(), state.reverb.decayTime());
            state.result = result.isValid() ? result : AcousticResult.OPEN;
            display.setAcoustics(state.result);
            if (NestworldConfig.DEBUG_STATS.get() && gameTime - state.lastLog >= 40) {
                state.lastLog = gameTime;
                LOGGER.info("Nestworld Video acoustics: direct L {}/{} R {}/{} (gain/high-freq), reverb send {} hf {} decay {}s",
                        String.format("%.2f", result.directGainL()), String.format("%.2f", result.directHfL()),
                        String.format("%.2f", result.directGainR()), String.format("%.2f", result.directHfR()),
                        String.format("%.2f", result.sendGain()), String.format("%.2f", result.sendHf()), String.format("%.2f", result.decayTime()));
            }
        } catch (RuntimeException e) {
            state.failed = true;
            display.setAcoustics(null);
            LOGGER.warn("Nestworld Video: room acoustics switched off for the screen at {}: {}", pos, e.toString());
        }
    }

    /** Probe points for one speaker: in front of the surface, with small shifts and (on wide screens) along its width. */
    private static List<V3> probes(V3 speaker, V3 right, V3 front, double half) {
        V3 base = speaker.add(front.scale(0.55)), up = new V3(0, 1, 0);
        List<V3> list = new ArrayList<>(7);
        list.add(base);
        list.add(base.add(right.scale(0.3))); list.add(base.sub(right.scale(0.3)));
        list.add(base.add(up.scale(0.3))); list.add(base.sub(up.scale(0.3)));
        if (half >= 1.5) { list.add(base.add(right.scale(half * 0.5))); list.add(base.sub(right.scale(half * 0.5))); }
        return list;
    }

    /** The last result computed for a screen (for diagnostics and tests), or null. */
    public AcousticResult lastResult(BlockPos pos) { State state = states.get(pos); return state == null ? null : state.result; }

    public void forget(BlockPos pos) { states.remove(pos); }
    public void clear() { states.clear(); }
}
