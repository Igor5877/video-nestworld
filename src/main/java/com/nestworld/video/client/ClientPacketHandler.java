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
                                                long positionMs, long serverMs,
                                                boolean autoNext, boolean repeat, boolean nextCancelled, String relayUrl, boolean adult) {
        Level world = Minecraft.getInstance().level;
        if (world == null || !world.isLoaded(pos)) return;
        if (world.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen) {
            com.nestworld.video.SyncClock.oneWay(serverMs);
            boolean wasPlaying = screen.isPlaying();
            String previousUrl = screen.getCurrentVideoUrl();
            long previous = screen.getTimelinePositionMs();
            screen.updatePlayback(url, title, playing, stopped, positionMs, serverMs, autoNext, repeat, nextCancelled, relayUrl, adult);
            ClientVideoManager.getInstance().reconcile(screen);
            long now = playing ? screen.getTimelinePositionMs() : positionMs;
            // Only a real change of the timeline moves the decoder (play, pause, seek, another video). A packet about
            // repeat, auto-next or the relay leaves it alone; small differences are evened out softly.
            if (playing != wasPlaying || !url.equals(previousUrl) || !playing || previous < 0 || Math.abs(now - previous) > 1000)
                ClientVideoManager.getInstance().syncPosition(pos, now);
        }
    }

    /** The server's periodic answer to this client's heartbeat for a screen. */
    public static void handleScreenClock(BlockPos pos, boolean playing, long positionMs, long serverMs, long clientSentMs) {
        com.nestworld.video.SyncClock.roundTrip(clientSentMs, serverMs);
        Level world = Minecraft.getInstance().level;
        if (world == null || !world.isLoaded(pos)) return;
        if (playing && world.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen) screen.applyClock(positionMs, serverMs);
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
