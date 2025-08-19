package com.nestworld.video.client;

import com.nestworld.video.CinemaMod;
import com.nestworld.video.block.ModBlocks;
import com.nestworld.video.client.gui.VideoScreenScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModBlocks.VIDEO_SCREEN_MENU.get(), VideoScreenScreen::new);
        });
    }
}
