package com.nestworld.video.client;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.nestworld.video.VideoConfig;
import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class ClientPacketHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public static void handleUpdateVideoScreen(BlockPos pos, String url, String title, boolean isPlaying) {
        Level world = Minecraft.getInstance().level;
        if (world == null) return;

        if (!isPlaying) {
            ClientVideoManager.getInstance().stopPlaying(pos);
            return;
        }

        if (!world.isLoaded(pos)) return;
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof VideoScreenBlockEntity screen) {
            screen.setVideoUrl(url);
            screen.setVideoTitle(title);
            screen.setPlaying(true);
            ClientVideoManager.getInstance().startPlaying(pos, url);
        }
    }

    public static void handleSyncVideos(String jsonConfig) {
        LOGGER.info("CLIENT: Received video config from server! Parsing...");
        try {
            VideoConfig config = GSON.fromJson(jsonConfig, VideoConfig.class);
            ClientData.setVideoConfig(config);
            LOGGER.info("CLIENT: Successfully parsed and stored video config. {} categories loaded.",
                    config.getCategories().size());
        } catch (Exception e) {
            LOGGER.error("CLIENT: Failed to parse video config from server.", e);
        }
    }
}
