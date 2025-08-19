package com.nestworld.video.network;

import com.nestworld.video.CinemaMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class Networking {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(CinemaMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void registerMessages() {
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
    }
}