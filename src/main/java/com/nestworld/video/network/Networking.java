package com.nestworld.video.network;

import com.nestworld.video.CinemaMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class Networking {

    private static final String PROTOCOL_VERSION = "6";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(CinemaMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void registerMessages() {
        INSTANCE.registerMessage(3, PacketBrowseRequest.class, PacketBrowseRequest::encode, PacketBrowseRequest::decode,
                PacketBrowseRequest::handle, java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        INSTANCE.registerMessage(4, PacketBrowseResult.class, PacketBrowseResult::encode, PacketBrowseResult::decode,
                PacketBrowseResult::handle, java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        INSTANCE.registerMessage(0,
                PacketSyncVideos.class,
                PacketSyncVideos::encode,
                PacketSyncVideos::decode,
                PacketSyncVideos::handle
        );
        
        INSTANCE.registerMessage(1,
                PacketControlVideo.class,
                PacketControlVideo::encode,
                PacketControlVideo::decode,
                PacketControlVideo::handle
        );
        
        INSTANCE.registerMessage(2,
                PacketUpdateVideoScreen.class,
                PacketUpdateVideoScreen::encode,
                PacketUpdateVideoScreen::decode,
                PacketUpdateVideoScreen::handle
        );
        INSTANCE.registerMessage(5,
                PacketScreenWatch.class,
                PacketScreenWatch::encode,
                PacketScreenWatch::decode,
                PacketScreenWatch::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER)
        );
        INSTANCE.registerMessage(6,
                PacketScreenViewers.class,
                PacketScreenViewers::encode,
                PacketScreenViewers::decode,
                PacketScreenViewers::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
        );
    }
}
