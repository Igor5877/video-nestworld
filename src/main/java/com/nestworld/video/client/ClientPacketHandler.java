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
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class ClientPacketHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    public static void handleBrowse(com.nestworld.video.network.PacketBrowseResult result) {
        if (Minecraft.getInstance().screen instanceof com.nestworld.video.client.gui.VideoScreenScreen screen)
            screen.acceptBrowse(result);
    }

    public static void handleUpdateVideoScreen(BlockPos pos, String url, String title, boolean playing, boolean stopped,
                                                long positionMs, long serverGameTime,
                                                boolean autoNext, boolean repeat, boolean nextCancelled) {
        Level world = Minecraft.getInstance().level;
        if (world == null || !world.isLoaded(pos)) return;
        if (world.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen) {
            long tickOffset = world.getGameTime() - serverGameTime;
            // Client and server clocks normally track each other, but avoid turning
            // a stale chunk packet into a huge seek when a world was reconnected.
            if (Math.abs(tickOffset) > 200) tickOffset = 0;
            long adjustedPosition = Math.max(0, positionMs + tickOffset * 50L);
            screen.updatePlayback(url, title, playing, stopped, adjustedPosition, autoNext, repeat, nextCancelled);
            ClientVideoManager.getInstance().reconcile(screen);
            ClientVideoManager.getInstance().syncPosition(pos, adjustedPosition);
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

    public static void handleScreenViewers(BlockPos pos, int count, List<String> names) {
        if (Minecraft.getInstance().screen instanceof com.nestworld.video.client.gui.VideoScreenScreen screen)
            screen.acceptViewerInfo(pos, count, names);
    }
}
