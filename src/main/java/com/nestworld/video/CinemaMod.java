package com.nestworld.video;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketSyncVideos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Files;

@Mod(CinemaMod.MOD_ID)
@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID)
public class CinemaMod {
    public static final String MOD_ID = "nestworldvideo";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static VideoConfig videoConfig;

    public CinemaMod() {
        LOGGER.info("Nestworld Video Mod has been initialized.");
        // Ініціалізуємо нашу мережу
        Networking.registerMessages();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("NESTWORLD_VIDEO: Server is starting, attempting to load videos.json...");
        File configFile = new File(event.getServer().getServerDirectory(), "config/videos.json");
        if (!configFile.exists()) {
            LOGGER.error("NESTWORLD_VIDEO: videos.json not found in config folder! Please create it.");
            return;
        }
        try {
            String jsonContent = new String(Files.readAllBytes(configFile.toPath()));
            videoConfig = new Gson().fromJson(jsonContent, VideoConfig.class);
            LOGGER.info("NESTWORLD_VIDEO: Successfully loaded {} categories from videos.json.", videoConfig.getCategories().size());
        } catch (Exception e) {
            LOGGER.error("NESTWORLD_VIDEO: Failed to read or parse videos.json.", e);
        }
    }

    // НОВИЙ МЕТОД: викликається, коли гравець заходить на сервер
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (videoConfig != null && event.getEntity() instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            LOGGER.info("NESTWORLD_VIDEO: Player {} joined. Sending video config...", player.getName().getString());

            // Відправляємо пакет з конфігурацією конкретному гравцю
            String json = new Gson().toJson(videoConfig);
            Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new PacketSyncVideos(json));
        }
    }
}