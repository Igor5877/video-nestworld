package com.nestworld.video;

import com.nestworld.video.block.*;
import com.nestworld.video.client.ClientData;
import com.nestworld.video.client.gui.VideoScreenScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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
import java.util.ArrayList;
import java.util.List;

/** Opt-in (-Pguitest): opens the screen-control menu directly (no network) and takes screenshots of its views. */
@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, value = Dist.CLIENT)
public class GuiSelfTest {
    static final BlockPos ORIGIN = new BlockPos(4, -60, 5);
    static boolean launched, scene, ready;
    static int ticks, capture;
    static long start = System.nanoTime();
    static volatile Throwable failure;
    static String shotPrefix = System.getProperty("nestworldvideo.guishots", "gui");

    static Category catalogue() {
        List<Video> videos = new ArrayList<>();
        for (String dub : new String[]{"Двоголосий закадровий", "Шалене чаювання"})
            for (int i = 1; i <= (dub.startsWith("Дво") ? 14 : 12); i++) {
                var v = new Video("Епізод " + i + " — Старша школа DxD — " + dub, "Епізод " + i, "https://example.invalid/" + dub.hashCode() + "/" + i + ".m3u8", "", "");
                v.setEpisodeInfo(String.valueOf(i), dub, "Старша школа DxD", "ashdi");
                videos.add(v);
            }
        var c = new Category("AniUA:high-school-dxd", videos);
        c.setDetails("Старша школа DxD", "", "Окремий реліз");
        return c;
    }

    static void click(String text) {
        var mc = Minecraft.getInstance();
        var b = mc.screen.children().stream().filter(e -> e instanceof Button bt && bt.getMessage().getString().equals(text)).map(e -> (Button) e).findFirst().orElseThrow(() -> new AssertionError("no button " + text));
        b.onPress();
    }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("nestworldvideo.guitest") || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        GLFW.glfwHideWindow(mc.getWindow().getWindow());
        mc.options.pauseOnLostFocus = false;
        if (failure != null) { failure.printStackTrace(); System.out.println("NESTWORLD GUI TEST FAIL"); mc.stop(); return; }
        if (System.nanoTime() - start > 480_000_000_000L) { System.out.println("NESTWORLD GUI TEST TIMEOUT"); mc.stop(); return; }
        if (!launched && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            launched = true;
            mc.options.renderDistance().set(4); mc.options.simulationDistance().set(5);
            mc.options.guiScale().set(2);
            mc.getWindow().setWindowed(1280, 800);
            mc.createWorldOpenFlows().createFreshLevel("Gui-Test-" + System.currentTimeMillis(),
                new LevelSettings("gui test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
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
                    level.setBlock(ORIGIN, ModBlocks.VIDEO_SCREEN_BLOCK.get().defaultBlockState().setValue(VideoScreenBlock.FACING, Direction.NORTH), 2);
                    var be = (VideoScreenBlockEntity) level.getBlockEntity(ORIGIN);
                    be.setVideo(catalogue().getVideos().get(0));
                    ready = true;
                } catch (Throwable t) { failure = t; }
            });
        }
        if (!ready) return;
        try {
            ticks++;
            if (ticks == 40) {
                ClientData.setVideoConfig(new VideoConfig(List.of(catalogue())));
                var be = (VideoScreenBlockEntity) mc.level.getBlockEntity(ORIGIN);
                mc.setScreen(new VideoScreenScreen(new VideoScreenMenu(0, mc.player.getInventory(), be), mc.player.getInventory(), Component.literal("Керування екраном")));
            }
            if (ticks == 70) capture = 1;                       // episode list
            if (ticks == 90) click("Пошук AniUA");
            if (ticks == 110) capture = 2;                      // search tab
            if (ticks == 120) click("Екран");
            if (ticks == 140) capture = 3;                      // build tab
            if (ticks == 160) { System.out.println("NESTWORLD GUI TEST PASS"); mc.stop(); }
        } catch (Throwable t) { failure = t; }
    }

    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || capture == 0) return;
        try (var img = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            Files.createDirectories(Path.of("screenshots")); img.writeToFile(Path.of("screenshots", shotPrefix + "-" + capture + ".png"));
        } catch (Exception ex) { failure = ex; }
        capture = 0;
    }
}
