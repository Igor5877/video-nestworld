package com.nestworld.video.client;

import com.mojang.logging.LogUtils;
import com.nestworld.video.NestworldConfig;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Screen tearing (horizontal bands) shows up on moving video when the frame rate is far above the monitor's refresh rate
 * and VSync is off. Players who play without VSync still get a clean picture because it is switched on only while a video
 * plays in front of them and restored afterwards. The saved game option is never changed, only the window's swap interval.
 */
final class AutoVsync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ENGAGE_TICKS = 20, RELEASE_TICKS = 100;
    private static boolean forced;
    private static int watchingTicks, idleTicks;

    private AutoVsync() {}

    /** Called every client tick; {@code watching} = a playing screen is close and in front of the player. */
    static void update(boolean watching) {
        if (!NestworldConfig.AUTO_VSYNC.get()) { release(); return; }
        Minecraft mc = Minecraft.getInstance();
        if (watching) {
            idleTicks = 0;
            if (++watchingTicks >= ENGAGE_TICKS && !forced && !mc.options.enableVsync().get()) {
                mc.getWindow().updateVsync(true);
                forced = true;
                LOGGER.info("Nestworld Video: VSync switched on while watching (video.auto_vsync) to avoid screen tearing");
            }
        } else {
            watchingTicks = 0;
            // Wait a few seconds before switching back so looking away briefly does not make the picture flicker.
            if (forced && ++idleTicks >= RELEASE_TICKS) release();
        }
    }

    /** Puts the swap interval back to what the player's own VSync option says. */
    static void release() {
        watchingTicks = idleTicks = 0;
        if (!forced) return;
        forced = false;
        Minecraft mc = Minecraft.getInstance();
        mc.getWindow().updateVsync(mc.options.enableVsync().get());
    }
}
