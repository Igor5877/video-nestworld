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
        event.register(Keybindings.CANCEL_NEXT_KEY);
        LOGGER.info("Registered keybindings for Nestworld Video");
    }

    @Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, value = Dist.CLIENT)
    public static class ForgeEvents {
        /** Under a shader pack, new video frames are enlarged before the world (and the pack) draws anything. */
        @SubscribeEvent
        public static void onRenderTick(net.minecraftforge.event.TickEvent.RenderTickEvent event) {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.START || Minecraft.getInstance().level == null) return;
            try { ShaderUpscaler.prepare(ClientVideoManager.getInstance().displays()); }
            catch (RuntimeException e) { LOGGER.warn("Nestworld Video: the picture could not be enlarged for the shader pack: {}", e.toString()); }
        }

        /** The screens' light data for the shader pack is written once per frame, before the world is drawn. */
        @SubscribeEvent
        public static void onRenderLevel(net.minecraftforge.client.event.RenderLevelStageEvent event) {
            if (event.getStage() != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) return;
            try { ClientVideoManager.getInstance().getGlow().upload(event.getCamera().getPosition()); }
            catch (RuntimeException e) { LOGGER.warn("Nestworld Video: screen light data could not be written: {}", e.toString()); }
        }

        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            while (Keybindings.CANCEL_NEXT_KEY.consumeClick()) NextUpToast.cancel();
            if (Keybindings.OPEN_MENU_KEY.consumeClick()) {
                if (Minecraft.getInstance().screen == null) {
                    var mc = Minecraft.getInstance();
                    if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                            && mc.level != null && mc.level.getBlockEntity(hit.getBlockPos()) instanceof com.nestworld.video.block.VideoScreenBlockEntity) {
                        com.nestworld.video.network.Networking.INSTANCE.sendToServer(
                            new com.nestworld.video.network.PacketControlVideo(hit.getBlockPos(), "open"));
                    } else if (mc.player != null) {
                        mc.player.displayClientMessage(Component.translatable("gui.nestworldvideo.aim"), true);
                    }
                }
            }
        }
    }
}