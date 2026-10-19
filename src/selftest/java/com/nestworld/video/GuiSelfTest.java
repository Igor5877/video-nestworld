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
    static int ticks, capture, shots, waitUntil;
    static long start = System.nanoTime();
    static volatile Throwable failure;
    static final boolean PLAY = Boolean.getBoolean("nestworldvideo.guiplay");
    static final String PLAY_URL = "file:///tmp/claude-0/spike/media/smpte.m3u8";
    static int restartAt;
    static final boolean HISTORY = Boolean.getBoolean("nestworldvideo.guihistory");
    static String shotPrefix = System.getProperty("nestworldvideo.guishots", "gui");

    static Category catalogue() {
        List<Video> videos = new ArrayList<>();
        for (String dub : new String[]{"Двоголосий закадровий", "Шалене Чаювання", "Шалене чаювання"})
            for (int i = 1; i <= (dub.startsWith("Дво") ? 14 : 12); i++) {
                var v = new Video("Епізод " + i + " — Старша школа DxD — " + dub, "Епізод " + i, dub.equals("Шалене Чаювання") ? "" : "https://example.invalid/" + dub.hashCode() + "/" + i + ".m3u8", "", "");
                v.setEpisodeInfo(String.valueOf(i), dub, "Старша школа DxD", "ashdi");
                videos.add(v);
            }
        if (PLAY) videos.set(0, withUrl(videos.get(0), PLAY_URL));
        var c = new Category("AniUA:high-school-dxd", videos);
        c.setDetails("Старша школа DxD", "", "Окремий реліз");
        return c;
    }

    static int field(Object screen, String name) throws ReflectiveOperationException {
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
            try { var f = c.getDeclaredField(name); f.setAccessible(true); return f.getInt(screen); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Video withUrl(Video v, String url) {
        var copy = new Video(v.getTitle(), "", url, "", "");
        copy.setEpisodeInfo(v.getEpisode(), v.getDub(), "Старша школа DxD", "ashdi");
        return copy;
    }

    static List<Category> library() {
        List<Category> all = new ArrayList<>();
        all.add(catalogue());
        String[][] shows = {
            {"Котяча вдячність", "Фільм", "1"}, {"Убивця демонів: До селища ковалів", "Сезон 3", "11"},
            {"Місто Демонів: Шінджюку", "Окремий реліз", "13"}, {"Демоничка з нашого району — 2 сезон", "Сезон 2", "12"},
            {"Дівчата і танки: Фінал — 4 частина", "Фільм", "1"}, {"Дуже довга назва тайтлу, яка точно не вміститься у два рядки картки", "Окремий реліз", "24"}};
        for (int i = 0; i < shows.length; i++) {
            List<Video> videos = new ArrayList<>();
            for (int e = 1; e <= Integer.parseInt(shows[i][2]); e++) {
                var v = new Video("Епізод " + e, "", "https://example.invalid/lib" + i + "/" + e + ".m3u8", "", "");
                v.setEpisodeInfo(String.valueOf(e), "Озвучка", shows[i][0], "ashdi");
                videos.add(v);
            }
            var c = new Category("AniUA:show-" + i, videos);
            c.setDetails(shows[i][0], "https://cdn.hikka.io/test/" + i + ".jpg", shows[i][1]);
            all.add(c);
        }
        return all;
    }

    static java.awt.image.BufferedImage poster(int index) {
        var img = new java.awt.image.BufferedImage(200, 300, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(40 + index * 30, 80, 200 - index * 20), 200, 300, new java.awt.Color(20, 20, 60)));
        g.fillRect(0, 0, 200, 300);
        g.setColor(new java.awt.Color(255, 255, 255, 200));
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 120));
        g.drawString(String.valueOf(index + 1), 55, 190);
        g.dispose();
        return img;
    }

    static void pause() { try { Thread.sleep(3); } catch (InterruptedException ignored) { } }

    static void click(String text) {
        var mc = Minecraft.getInstance();
        var b = mc.screen.children().stream().filter(e -> e instanceof Button bt && (bt.getMessage().getString().equals(text) || bt.getMessage().getString().trim().endsWith(text))).map(e -> (Button) e).findFirst().orElseThrow(() -> new AssertionError("no button " + text));
        b.onPress();
    }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("nestworldvideo.guitest") || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        GLFW.glfwHideWindow(mc.getWindow().getWindow());
        mc.options.pauseOnLostFocus = false;
        if (failure != null) { failure.printStackTrace(); System.out.println("NESTWORLD GUI TEST FAIL"); mc.stop(); return; }
        if (System.nanoTime() - start > 900_000_000_000L) { System.out.println("NESTWORLD GUI TEST TIMEOUT"); mc.stop(); return; }
        if (!launched && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            launched = true;
            mc.options.renderDistance().set(4); mc.options.simulationDistance().set(5);
            mc.options.guiScale().set(2);
            boolean small = Integer.getInteger("nestworldvideo.guiscale", 2) > 2;
            mc.getWindow().setWindowed(small ? 870 : 1280, small ? 530 : 800);
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
            if (ticks == 30) mc.resizeDisplay();
            if (ticks == 40) {
                for (int i = 0; i < 6; i++) com.nestworld.video.client.gui.PosterCache.put("https://cdn.hikka.io/test/" + i + ".jpg", poster(i));
                var lib = library();
                ClientData.setVideoConfig(new VideoConfig(lib));
                if (HISTORY) {
                    
                    com.nestworld.video.client.WatchHistory.useMemory();
                    var dxd = lib.get(0);
                    var firstDub = dxd.getVideos().get(0).getDub();
                    var dubVideos = dxd.getVideos().stream().filter(v -> v.getDub().equals(firstDub)).toList();
                    for (int i = 0; i < 3; i++) { com.nestworld.video.client.WatchHistory.record(dxd, dubVideos.get(i), 1_400_000, 1_440_000, 1080, true); pause(); }
                    com.nestworld.video.client.WatchHistory.record(dxd, dubVideos.get(3), 612_000, 1_440_000, 1080, false); pause();
                    var killer = lib.get(2);   // watched a whole episode: shelf offers the next one
                    com.nestworld.video.client.WatchHistory.record(killer, killer.getVideos().get(0), 1_400_000, 1_440_000, 720, true); pause();
                    var city = lib.get(3);     // barely started + favourite
                    com.nestworld.video.client.WatchHistory.record(city, city.getVideos().get(1), 300_000, 1_380_000, 720, false);
                    com.nestworld.video.client.WatchHistory.toggleFavorite(lib.get(1));
                    com.nestworld.video.client.WatchHistory.toggleFavorite(city);
                }
                var be = (VideoScreenBlockEntity) mc.level.getBlockEntity(ORIGIN);
                mc.setScreen(new VideoScreenScreen(new VideoScreenMenu(0, mc.player.getInventory(), be), mc.player.getInventory(), Component.literal("Керування екраном")));
            }
            if (PLAY) {
                if (ticks == 45) mc.getSingleplayerServer().execute(() -> ((VideoScreenBlockEntity) mc.getSingleplayerServer().overworld().getBlockEntity(ORIGIN)).play());
                var display = com.nestworld.video.client.ClientVideoManager.getInstance().getDisplay(ORIGIN);
                if (Boolean.getBoolean("nestworldvideo.guiend")) {
                    var be = (VideoScreenBlockEntity) mc.level.getBlockEntity(ORIGIN);
                    if (be == null || ticks < 60) return;
                    if (ticks % 200 == 0) System.out.println("END-TEST t=" + ticks + " url=" + be.getCurrentVideoUrl().substring(Math.max(0, be.getCurrentVideoUrl().length() - 14)) + " playing=" + be.isPlaying() + " stopped=" + be.isStopped()
                            + " display=" + (display == null ? "none" : display.getPlaybackTime() + "/" + display.getDuration()));
                    if (!be.getCurrentVideoUrl().equals(PLAY_URL)) { System.out.println("END HANDLED: moved to " + be.getCurrentVideoUrl() + " after " + ticks + " ticks"); System.out.println("NESTWORLD GUI TEST PASS"); mc.stop(); }
                    else if (be.isStopped() && ticks > 100) { System.out.println("END HANDLED: stopped after " + ticks + " ticks"); System.out.println("NESTWORLD GUI TEST PASS"); mc.stop(); }
                    return;
                }
                if (Boolean.getBoolean("nestworldvideo.guirestart")) {
                    var mgr = com.nestworld.video.client.ClientVideoManager.getInstance();
                    if (restartAt == 0 && display != null && display.getPlaybackTime() > 12000) restartAt = ticks;
                    if (restartAt > 0) {
                        int dt = ticks - restartAt;
                        if (dt < 50 && dt % 10 == 0) { System.out.println("RESTART #" + (dt / 10) + " at media " + (display == null ? -1 : display.getPlaybackTime())); mgr.restartAll(); }
                        if (dt % 20 == 0 && dt < 400) System.out.println("T+" + dt + " display=" + (display == null ? "none" : display.getPlaybackTime() + " playing=" + display.isPlaying() + " buf=" + display.isBuffering())
                                + " screen=" + ((VideoScreenBlockEntity) mc.level.getBlockEntity(ORIGIN)).getPlaybackPositionMs());
                        if (dt == 400) { System.out.println("NESTWORLD GUI TEST PASS"); mc.stop(); }
                    }
                    return;
                }
                if (shots == 0 && display != null && display.getPlaybackTime() > 6000) { shots = 1; capture = 1; waitUntil = ticks + 10; }   // playing
                if (shots == 1 && ticks == waitUntil) { click("Пауза"); waitUntil = ticks + 40; shots = 2; }
                if (shots == 2 && ticks == waitUntil) { capture = 2; waitUntil = ticks + 20; shots = 3; }                                   // paused
                if (shots == 3 && ticks == waitUntil) { System.out.println("NESTWORLD GUI TEST PASS"); mc.stop(); }
                return;
            }
            if (Boolean.getBoolean("nestworldvideo.guisettings")) {
                if (ticks == 50) mc.setScreen(new com.nestworld.video.client.gui.NestworldSettingsScreen(null));
                if (ticks == 70) capture = 1;
                if (ticks == 75) mc.screen.mouseScrolled(100, 100, -5);
                if (ticks == 95) capture = 2;
                if (ticks == 100) { System.out.println("NESTWORLD GUI TEST PASS"); mc.stop(); }
                return;
            }
            if (HISTORY) {
                if (ticks == 70) capture = 1;                   // episode list with marks
                if (ticks == 90) click("Бібліотека");
                if (ticks == 110) capture = 2;                  // library with the resume shelf
                if (ticks == 120) click("Сорт.: Останні");
                if (ticks == 125) click("Сорт.: А–Я");
                if (ticks == 140) capture = 3;                  // A-Z
                if (ticks == 150) click("Сорт.: Не дивився");
                if (ticks == 170) capture = 4;                  // unwatched only
                if (ticks == 180) click("Сорт.: Останні");
                if (ticks == 185) click("☆");
                if (ticks == 205) capture = 5;                  // favourites only
                if (ticks == 215) click("★");
                if (ticks == 225) click("Старша школа DxD");     // opens the resume card (first) -> episodes
                if (ticks == 260) capture = 6;
                if (ticks == 270) { System.out.println("NESTWORLD GUI TEST PASS"); mc.stop(); }
                return;
            }
            if (ticks == 70) capture = 1;                       // episode list
            if (ticks == 90) click("Пошук AniUA");
            if (ticks == 110) capture = 2;                      // search tab
            if (ticks == 120) {                                 // click "↩ До списку серій" on the now-playing card
                var scr = mc.screen;
                int left = field(scr, "leftPos"), top = field(scr, "topPos"), w = field(scr, "imageWidth"), h = field(scr, "imageHeight");
                double x = left + w - 12 - 30, y = top + h - 84 + 23;
                if (!scr.mouseClicked(x, y, 0)) throw new AssertionError("link click not handled");
            }
            if (ticks == 140) {
                boolean back = mc.screen.children().stream().anyMatch(e -> e instanceof Button b && b.getMessage().getString().contains("Серія 1"));
                if (!back) throw new AssertionError("did not return to the episode list");
                capture = 3;                                    // back on the episode list
            }
            if (ticks == 160) click("Екран");
            if (ticks == 180) capture = 4;                      // build tab
            if (ticks == 200) click("Бібліотека");
            if (ticks == 220) capture = 5;                      // library as cards
            if (ticks == 230) click("Старша школа DxD");        // open the release: voices as cards
            if (ticks == 250) capture = 6;
            if (ticks == 270) { System.out.println("NESTWORLD GUI TEST PASS"); mc.stop(); }
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
