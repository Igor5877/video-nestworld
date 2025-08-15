package com.nestworld.video.client;

import com.mojang.logging.LogUtils;
import com.nestworld.video.CinemaMod;
import com.nestworld.video.client.gui.VideoSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onKeyRegister(RegisterKeyMappingsEvent event) {
        event.register(Keybindings.OPEN_MENU_KEY);
        LOGGER.info("Registered keybindings for Nestworld Video");
    }

    @Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, value = Dist.CLIENT)
    public static class ForgeEvents {
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            if (Keybindings.OPEN_MENU_KEY.consumeClick()) {
                if (Minecraft.getInstance().screen == null) {
                    Minecraft.getInstance().setScreen(new VideoSelectionScreen());
                }
            }
        }
    }
}