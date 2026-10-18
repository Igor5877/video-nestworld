package com.nestworld.video;

import com.nestworld.video.block.*;
import com.nestworld.video.client.ClientVideoManager;
import com.nestworld.video.client.gui.VideoScreenScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;

@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, value = Dist.CLIENT)
public class ScreenSelfTest {
    static boolean launched, scene;
    static volatile boolean checksDone;
    static volatile Throwable failure;
    static int ticks, capture;
    static int browserStage, stageTick;
    static long start = System.nanoTime();
    static final BlockPos origin = new BlockPos(4, -60, 5);
    static final BlockPos clicked = new BlockPos(0, -59, 5);
    static com.nestworld.video.client.util.Display pausedDisplay;

    static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
    static VideoScreenBlockEntity be(ServerLevel level, BlockPos pos) { return (VideoScreenBlockEntity)level.getBlockEntity(pos); }
    static void wall(ServerLevel level, BlockPos start, Direction facing, int cols, int rows) {
        var state = ModBlocks.VIDEO_SCREEN_BLOCK.get().defaultBlockState().setValue(VideoScreenBlock.FACING, facing);
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++)
            level.setBlock(start.relative(facing.getCounterClockWise(), c).above(r), state, 2);
    }
    static void tests(ServerLevel level) {
        // Explicitly load the isolated test region. Production scans never load chunks.
        for (int x = 1; x <= 12; x++) for (int z = 7; z <= 10; z++) level.getChunk(x,z);
        int n = 0;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos p = new BlockPos(160, n++ * 10, 145);
            wall(level, p, facing, 3, 2);
            BlockPos tip = p.relative(facing.getCounterClockWise(), 2).above();
            var result = be(level, tip).assemble();
            require(result.valid() && result.origin().equals(p) && result.cols() == 3 && result.rows() == 2, "orientation " + facing);
            require(be(level, tip).controller() == be(level, p), "controller " + facing);
            var restored = new VideoScreenBlockEntity(tip, level.getBlockState(tip));
            restored.load(be(level, tip).getUpdateTag());
            require(restored.getMasterPos().equals(p) && restored.getTileCol() == 2 && restored.getTileRow() == 1, "NBT");
            level.removeBlock(tip, false);
            require(be(level, p).getScreenCols() == 1, "break slave resets master");
            require(!ScreenLayout.detect(level,p).valid(), "hole rejected");
        }
        BlockPos p = new BlockPos(128, 0, 128);
        wall(level, p, Direction.NORTH, 100, 100);
        long time = System.nanoTime();
        var result = be(level, p.west(50).above(50)).assemble();
        require(result.valid() && result.cols() == 100 && result.rows() == 100 && result.tiles().size() == 10000, "100x100");
        for (BlockPos tile : result.tiles()) require(be(level,tile).controller() == be(level,p), "large ownership");
        System.out.println("NESTWORLD TEST 100x100 PASS " + (System.nanoTime()-time)/1_000_000 + " ms");
        wall(level, p.west(100), Direction.NORTH, 1, 100);
        require(ScreenLayout.detect(level,p).error().equals("large"), "101 width rejected");
        require(be(level,p).getScreenCols() == 100, "invalid preview leaves layout intact");
        for (int r=0;r<100;r++) level.removeBlock(p.west(100).above(r),false);
        level.removeBlock(p.west(50).above(50),false);
        require(ScreenLayout.detect(level,p).error().equals("holes"), "large hole");
        require(be(level,p).getScreenCols() == 1 && be(level,p.west(99).above(99)).isMaster(), "large break cleanup");
        wall(level, p.west(50).above(50), Direction.SOUTH,1,1);
        require(ScreenLayout.detect(level,p).error().equals("facing"), "mixed facing");
        wall(level, p.west(50).above(50), Direction.NORTH,1,1);
        require(be(level,p).assemble().valid(), "repair reassembly");
        level.removeBlock(p,false);
        require(be(level,p.west(99).above(99)).isMaster(), "break master cleanup");
        BlockPos extension = new BlockPos(175, 60, 145);
        wall(level, extension, Direction.NORTH, 2, 2);
        be(level,extension).assemble();
        be(level,extension).setVideo(new Video("Preserved film", "", "https://example.invalid/film.mp4", "", ""));
        wall(level, extension.west(2), Direction.NORTH, 1, 2);
        require(be(level,extension.west(2)).assemble().valid(), "extend from new tile");
        require(be(level,extension).getCurrentVideoTitle().equals("Preserved film"), "extension preserves selection");
        System.out.println("NESTWORLD LAYOUT TESTS PASS: four facings, any tile, NBT, holes, facing, 100x100, overflow, break, repair");
    }
    static void click(String text) {
        var mc = Minecraft.getInstance();
        require(mc.screen instanceof VideoScreenScreen, "screen open before " + text);
        var button = mc.screen.children().stream().filter(e -> e instanceof Button b && b.getMessage().getString().equals(text)).map(e -> (Button)e).findFirst().orElseThrow();
        require(button.active, "button active " + text);
        button.onPress();
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("nestworldvideo.selftest") || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        GLFW.glfwHideWindow(mc.getWindow().getWindow());
        mc.options.pauseOnLostFocus = false;
        if (failure != null) { failure.printStackTrace(); mc.stop(); return; }
        if (System.nanoTime()-start > 240_000_000_000L) { System.err.println("NESTWORLD TEST TIMEOUT"); mc.stop(); return; }
        if (!launched && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            launched = true;
            mc.options.renderDistance().set(6); mc.options.simulationDistance().set(5);
            mc.options.framerateLimit().set(60); mc.options.guiScale().set(2);
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.resizeDisplay(); mc.getWindow().setWindowed(1280, 800);
            mc.createWorldOpenFlows().createFreshLevel("Screen-Test-"+System.currentTimeMillis(),
                new LevelSettings("Screen tests", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(42L,false,false),
                reg -> reg.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        mc.mouseHandler.releaseMouse();
        if (!scene) {
            scene = true;
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var level = mc.getSingleplayerServer().overworld();
                    tests(level);
                    wall(level,origin,Direction.NORTH,8,4);
                    var source = mc.getSingleplayerServer().createCommandSourceStack().withSuppressedOutput();
                    for(String cmd : new String[]{"time set day", "weather clear", "gamerule doMobSpawning false", "tp @a 0 -60 0 0 -8"})
                        mc.getSingleplayerServer().getCommands().performPrefixedCommand(source,cmd);
                    checksDone = true;
                } catch(Throwable t) { failure = t; }
            });
        }
        if (!checksDone) return;
        try {
            ticks++;
            if (ticks == 60) mc.getSingleplayerServer().execute(() -> {
                var p = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
                NetworkHooks.openScreen(p, be(p.serverLevel(),clicked),clicked);
            });
            if (ticks == 80) { click("Екран"); capture = 1; }
            if (ticks == 100) click("Об’єднати");
            if (ticks == 120) {
                require(((VideoScreenBlockEntity)mc.level.getBlockEntity(clicked)).controller().getScreenCols() == 8,"network assemble");
                click("Бібліотека"); capture = 2;
            }
            if (ticks == 140) {
                click("›");
                var edit = (EditBox)mc.screen.children().stream().filter(c -> c instanceof EditBox).findFirst().orElseThrow();
                edit.setValue("Тестовий фільм");
                click("Тестовий фільм");
            }
            if (ticks == 160) {
                var root = ((VideoScreenBlockEntity)mc.level.getBlockEntity(clicked)).controller();
                require(root.getCurrentVideoTitle().equals("Тестовий фільм"), "selection while stopped");
                click("Відтворити");
            }
            if (ticks == 240) {
                pausedDisplay = ClientVideoManager.getInstance().getDisplay(origin);
                require(pausedDisplay != null && pausedDisplay.getTextureId() != -1, "real video decoded");
                click("Пауза");
            }
            if (ticks == 260) {
                require(ClientVideoManager.getInstance().getDisplay(origin) == pausedDisplay, "pause preserves decoder");
                click("Відтворити");
            }
            if (ticks == 280) {
                require(ClientVideoManager.getInstance().getDisplay(origin) == pausedDisplay, "resume same decoder");
                capture=3;
            }
            if (ticks == 300) mc.screen.onClose();
            if (ticks == 320) capture=4;
            if (ticks == 340) mc.getSingleplayerServer().execute(() -> {
                var p = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
                NetworkHooks.openScreen(p,be(p.serverLevel(),clicked),clicked);
            });
            if (ticks == 360) {
                click("Зупинити");
                mc.getWindow().setWindowed(1280, 960); mc.options.guiScale().set(4); mc.resizeDisplay();
                capture=5;
            }
            if (ticks == 380) { click("Екран"); capture=6; }
            if (ticks == 400) click("Роз’єднати");
            if (ticks == 420) {
                require(((VideoScreenBlockEntity)mc.level.getBlockEntity(clicked)).isMaster(), "network separate");
                require(ClientVideoManager.getInstance().getDisplay(origin) == null, "decoder released");
                System.out.println("NESTWORLD SELFTEST PASS: GUI, paging, search, selection, network assembly, real video, pause/resume, separation, small GUI");
                click("Пошук AniUA");
                mc.getWindow().setWindowed(1280,800); mc.options.guiScale().set(2); mc.resizeDisplay();
                var edit = (EditBox)mc.screen.children().stream().filter(c -> c instanceof EditBox).findFirst().orElseThrow();
                edit.setValue("неос");
                edit.setFocused(true);
                mc.screen.keyPressed(257, 0, 0);
                browserStage=1; stageTick=ticks;
            }
            if (browserStage > 0 && ticks > stageTick + 30) browserTests(mc);
        } catch(Throwable t) { failure=t; }
    }
    static java.util.List<Button> buttons(Minecraft mc) {
        return mc.screen.children().stream().filter(c -> c instanceof Button b && b.visible).map(c -> (Button)c).toList();
    }
    static void browserTests(Minecraft mc) throws Exception {
        if (browserStage == 1) {
            var found = buttons(mc).stream().filter(b -> b.getMessage().getString().equals("Неосяжна блакить (2018)")).findFirst();
            if (found.isEmpty()) return;
            capture=7; browserStage=6; stageTick=ticks;
        } else if (browserStage == 6) {
            click("Неосяжна блакить (2018)");browserStage=2;stageTick=ticks;
        } else if (browserStage == 2) {
            var found = buttons(mc).stream().filter(b -> b.getMessage().getString().startsWith("Togarashi")).findFirst();
            if (found.isEmpty()) return;
            // Use the real client texture to verify the API poster was decoded and uploaded.
            var field = VideoScreenScreen.class.getDeclaredField("poster"); field.setAccessible(true);
            var poster=field.get(mc.screen); var texture=poster.getClass().getDeclaredMethod("texture"); texture.setAccessible(true);
            if (texture.invoke(poster)==null) return;
            capture=8; browserStage=7; stageTick=ticks;
        } else if (browserStage == 7) {
            buttons(mc).stream().filter(b->b.getMessage().getString().startsWith("Togarashi")).findFirst().orElseThrow().onPress();
            browserStage=3;stageTick=ticks;
        } else if (browserStage == 3) {
            var episodes=buttons(mc).stream().filter(b->b.getMessage().getString().startsWith("Серія ")).toList();
            require(episodes.size()>=2 && episodes.get(0).getMessage().getString().equals("Серія 1")
                    && episodes.get(1).getMessage().getString().equals("Серія 2"),"ascending episode buttons");
            capture=9;
            mc.getWindow().setWindowed(1280,960); mc.options.guiScale().set(4); mc.resizeDisplay();
            browserStage=4;stageTick=ticks;
        } else if (browserStage == 4) {
            mc.getWindow().setWindowed(1280,800); mc.options.guiScale().set(2); mc.resizeDisplay();
            capture=10;browserStage=8;stageTick=ticks;
        } else if (browserStage == 8) {
            click("Серія 1");browserStage=9;stageTick=ticks;
        } else if (browserStage == 9) {
            var root=((VideoScreenBlockEntity)mc.level.getBlockEntity(clicked)).controller();
            require(root.getCurrentVideoTitle().contains("Епізод 1"),"AniUA episode selected through server catalogue");
            click("Сезони");browserStage=5;stageTick=ticks;
        } else if (browserStage == 5) {
            var second=buttons(mc).stream().filter(b->b.getMessage().getString().contains("2 сезон")).findFirst();
            if(second.isEmpty()) return;
            var releaseButtons=buttons(mc).stream().filter(b->b.getMessage().getString().startsWith("Неосяжна")).toList();
            require(releaseButtons.get(0).getMessage().getString().contains("2018"),"undated announced season sorted last");
            capture=11;
            System.out.println("NESTWORLD BROWSER PASS: partial search via Enter, explicit title, real API episodes, poster texture, numeric order, franchise seasons, both GUI scales");
            browserStage=0;mc.stop();
        }
    }
    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || capture == 0) return;
        try (var img=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            Files.createDirectories(Path.of("screenshots")); img.writeToFile(Path.of("screenshots","screen-"+capture+".png"));
        } catch(Exception ex) { failure=ex; }
        capture=0;
    }
}
