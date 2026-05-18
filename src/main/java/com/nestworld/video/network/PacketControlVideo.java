package com.nestworld.video.network;

import com.mojang.logging.LogUtils;
import com.nestworld.video.CinemaMod;
import com.nestworld.video.Video;
import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.function.Supplier;

public class PacketControlVideo {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private final BlockPos blockPos;
    private final String action;
    private final String videoUrl;
    private final String videoTitle;
    
    public PacketControlVideo(BlockPos blockPos, String action) {
        this(blockPos, action, "", "");
    }
    
    public PacketControlVideo(BlockPos blockPos, String action, String videoUrl, String videoTitle) {
        this.blockPos = blockPos;
        this.action = action;
        this.videoUrl = videoUrl;
        this.videoTitle = videoTitle;
    }
    
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(blockPos);
        buffer.writeUtf(action);
        buffer.writeUtf(videoUrl);
        buffer.writeUtf(videoTitle);
    }
    
    public static PacketControlVideo decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String action = buffer.readUtf();
        String url = buffer.readUtf();
        String title = buffer.readUtf();
        return new PacketControlVideo(pos, action, url, title);
    }
    
    public static void handle(PacketControlVideo message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            
            if (player.level().getBlockEntity(message.blockPos) instanceof VideoScreenBlockEntity videoScreen) {
                LOGGER.info("Player {} executing action {} on video screen at {}", 
                    player.getName().getString(), message.action, message.blockPos);
                
                switch (message.action) {
                    case "play":
                        videoScreen.play();
                        break;
                    case "pause":
                        videoScreen.pause();
                        break;
                    case "stop":
                        videoScreen.stop();
                        break;
                    case "select":
                        LOGGER.info("Selected video: {} with URL: {}", message.videoTitle, message.videoUrl);
                        videoScreen.setVideo(createVideoFromData(message.videoUrl, message.videoTitle));
                        break;
                    case "layout":
                        try {
                            int cols = Integer.parseInt(message.videoUrl);
                            int rows = Integer.parseInt(message.videoTitle);
                            if (cols >= 1 && rows >= 1 && cols <= 16 && rows <= 16) {
                                videoScreen.applyLayout(cols, rows);
                                LOGGER.info("Applied {}x{} screen layout at {}", cols, rows, message.blockPos);
                            }
                        } catch (NumberFormatException e) {
                            LOGGER.warn("Invalid layout dimensions: {}x{}", message.videoUrl, message.videoTitle);
                        }
                        break;
                    case "reset_layout":
                        videoScreen.resetLayout();
                        LOGGER.info("Reset screen layout at {}", message.blockPos);
                        break;
                }
            }
        });
        context.setPacketHandled(true);
    }
    
    private static Video createVideoFromData(String url, String title) {
        return new Video(title, "", url, "", "");
    }
}