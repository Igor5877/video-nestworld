package com.nestworld.video;

import com.nestworld.video.block.*;
import com.nestworld.video.client.ClientVideoManager;
import com.nestworld.video.client.util.Display;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in (-Pffmpegtest): plays a stream on a wall through the server API and reports what the decoder does. */
@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, value = Dist.CLIENT)
public class FfmpegSelfTest {
    static final BlockPos ORIGIN = new BlockPos(4, -60, 5);
    static boolean launched, scene, serverReady;
    static int ticks, capture;
    static long start = System.nanoTime(), playStart;
    static volatile Throwable failure;
    static boolean sawFrame;
    static final String URL = System.getProperty("nestworldvideo.testurl", "file:///tmp/claude-0/spike/media/smpte.m3u8");

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("nestworldvideo.ffmpegtest") || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        GLFW.glfwHideWindow(mc.getWindow().getWindow());
        mc.options.pauseOnLostFocus = false;
        if (failure != null) { failure.printStackTrace(); System.out.println("NESTWORLD FFMPEG TEST FAIL"); mc.stop(); return; }
        if (System.nanoTime() - start > 600_000_000_000L) { System.out.println("NESTWORLD FFMPEG TEST TIMEOUT"); mc.stop(); return; }
        if (!launched && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            launched = true;
            mc.options.renderDistance().set(6); mc.options.simulationDistance().set(5);
            mc.options.framerateLimit().set(60); mc.options.guiScale().set(2);
            mc.getWindow().setWindowed(1280, 720);
            mc.createWorldOpenFlows().createFreshLevel("Ffmpeg-Test-" + System.currentTimeMillis(),
                new LevelSettings("ffmpeg test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(7L, false, false),
                reg -> reg.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        mc.mouseHandler.releaseMouse();
        if (!scene) {
            scene = true;
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var level = mc.getSingleplayerServer().overworld();
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 10; z++) level.getChunk(x >> 4, z >> 4);
                    var state = ModBlocks.VIDEO_SCREEN_BLOCK.get().defaultBlockState().setValue(VideoScreenBlock.FACING, Direction.NORTH);
                    for (int r = 0; r < 4; r++) for (int c = 0; c < 8; c++)
                        level.setBlock(ORIGIN.relative(Direction.NORTH.getCounterClockWise(), c).above(r), state, 2);
                    var master = (VideoScreenBlockEntity) level.getBlockEntity(ORIGIN);
                    var layout = master.assemble();
                    if (!layout.valid()) throw new AssertionError("assemble " + layout.error());
                    var source = mc.getSingleplayerServer().createCommandSourceStack().withSuppressedOutput();
                    for (String cmd : new String[]{"time set day", "weather clear", "tp @a 0 -60 0 0 -8"})
                        mc.getSingleplayerServer().getCommands().performPrefixedCommand(source, cmd);
                    master.setVideo(new Video("FFmpeg test", "", URL, "", ""));
                    master.play();
                    playStart = System.nanoTime();
                    serverReady = true;
                    System.out.println("NESTWORLD FFMPEG TEST: wall assembled, playing " + URL);
                } catch (Throwable t) { failure = t; }
            });
        }
        if (!serverReady) return;
        ticks++;
        Display d = ClientVideoManager.getInstance().getDisplay(ORIGIN);
        if (d != null && d.getTextureId() != -1) sawFrame = true;
        if (ticks % 40 == 0) {
            System.out.printf("NESTWORLD FFMPEG TEST t=%ds display=%s texture=%s dims=%s time=%dms/%dms playing=%b buffering=%b broken=%b%n",
                ticks / 20, d == null ? "none" : d.getClass().getSimpleName(), d == null ? "-" : d.getTextureId(),
                d == null ? "-" : d.getDimensions(), d == null ? -1 : d.getPlaybackTime(), d == null ? -1 : d.getDuration(),
                d != null && d.isPlaying(), d != null && d.isBuffering(), d != null && d.isBroken());
        }
        // Under a shader pack: the same colour bars with increasingly strong colour correction.
        int[][] tints = {{75, 82}, {65, 72}, {55, 62}};
        for (int k = 0; k < tints.length; k++) {
            int begin = 20 * (13 + k * 7);
            if (ticks == begin) { NestworldConfig.SHADER_TINT_RED.set(tints[k][0]); NestworldConfig.SHADER_TINT_GREEN.set(tints[k][1]); NestworldConfig.SHADER_TINT_BLUE.set(100); }
            if (ticks == begin + 20 * 5) capture = k + 1;
        }
        if (ticks == 20 * 36) {
            // The clip is short: by now it has finished and the screen has stopped, so remember earlier frames.
            boolean ok = sawFrame;
            System.out.println(ok ? "NESTWORLD FFMPEG TEST PASS: frames are being displayed" : "NESTWORLD FFMPEG TEST FAIL: no frame displayed");
            mc.stop();
        }
    }

    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || capture == 0) return;
        try (var img = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            Files.createDirectories(Path.of("screenshots")); img.writeToFile(Path.of("screenshots", "ffmpeg-" + capture + ".png"));
        } catch (Exception ex) { failure = ex; }
        capture = 0;
    }
}
