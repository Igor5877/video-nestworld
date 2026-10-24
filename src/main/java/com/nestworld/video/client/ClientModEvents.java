package com.nestworld.video.client;

import com.nestworld.video.CinemaMod;
import com.nestworld.video.block.ModBlocks;
import com.nestworld.video.client.gui.VideoScreenScreen;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;

@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Register the tick event handler
        MinecraftForge.EVENT_BUS.addListener(ClientModEvents::onClientTick);

        // This is a good place to enqueue client-specific tasks
        event.enqueueWork(() -> {
            MenuScreens.register(ModBlocks.VIDEO_SCREEN_MENU.get(), VideoScreenScreen::new);
            // Embeddium's video settings get their own "Nestworld Video" page.
            if (net.minecraftforge.fml.ModList.get().isLoaded("embeddium"))
                com.nestworld.video.client.integration.EmbeddiumOptions.register();
            // Mods list -> Nestworld Video -> Config opens the same settings screen.
            net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                    net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new com.nestworld.video.client.gui.NestworldSettingsScreen(parent)));

                Runtime.getRuntime().addShutdownHook(new Thread(() -> ClientVideoManager.getInstance().shutdown()));

            // Stop all videos when the player leaves a world/server.
            MinecraftForge.EVENT_BUS.addListener(ClientModEvents::onPlayerLogout);
            // Download and unpack FFmpeg while the player is still looking around, not when the first video starts.
            MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) ->
                    com.nestworld.video.ffmpeg.FfmpegLibs.prepareAsync(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()));
        });
    }

    @SubscribeEvent
    public static void registerShaders(final RegisterShadersEvent event) throws java.io.IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(CinemaMod.MOD_ID, "video_screen"), DefaultVertexFormat.POSITION_TEX),
                shader -> videoScreenShader = shader);
    }

    /** Bicubic video sampler; null until the client has loaded shaders, then the renderer falls back to vanilla. */
    private static ShaderInstance videoScreenShader;
    public static ShaderInstance videoScreenShader() { return videoScreenShader; }

    @SubscribeEvent
    public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlocks.VIDEO_SCREEN_BLOCK_ENTITY.get(), VideoScreenBlockRenderer::new);
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ClientVideoManager.getInstance().tick();
        }
    }

    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientVideoManager.getInstance().shutdown();
        ClientData.setVideoConfig(null);
        com.nestworld.video.SyncClock.reset();   // the next server has a clock of its own
    }
}
