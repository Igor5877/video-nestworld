package com.nestworld.video.client;

import com.nestworld.video.CinemaMod;
import com.nestworld.video.block.ModBlocks;
import com.nestworld.video.client.gui.VideoScreenScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.network.ClientPlayerNetworkEvent;
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

                Runtime.getRuntime().addShutdownHook(new Thread(() -> ClientVideoManager.getInstance().shutdown()));

            // Stop all videos when the player leaves a world/server.
            MinecraftForge.EVENT_BUS.addListener(ClientModEvents::onPlayerLogout);
        });
    }

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
    }
}
