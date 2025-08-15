package com.nestworld.video;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Files;

@Mod(CinemaMod.MOD_ID)
@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID)
public class CinemaMod {
    public static final String MOD_ID = "cinemamod";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static VideoConfig videoConfig;

    public CinemaMod() {
        LOGGER.info("Cinema Mod has been initialized.");
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("CINEMA_MOD: Server is starting, attempting to load videos.json...");

        // Визначаємо шлях до файлу: <папка_запуску_сервера>/config/videos.json
        File configFile = new File(event.getServer().getServerDirectory(), "config/videos.json");

        if (!configFile.exists()) {
            LOGGER.error("CINEMA_MOD: videos.json not found in config folder! Please create it.");
            return;
        }

        try {
            String jsonContent = new String(Files.readAllBytes(configFile.toPath()));
            videoConfig = new Gson().fromJson(jsonContent, VideoConfig.class);
            LOGGER.info("CINEMA_MOD: Successfully loaded {} categories from videos.json.", videoConfig.getCategories().size());
        } catch (Exception e) {
            LOGGER.error("CINEMA_MOD: Failed to read or parse videos.json.", e);
        }
    }
}