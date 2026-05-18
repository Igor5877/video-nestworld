package com.nestworld.video.network;

import com.nestworld.video.client.ClientPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketUpdateVideoScreen {

    final BlockPos pos;
    final String url;
    final String title;
    final boolean isPlaying;

    public PacketUpdateVideoScreen(BlockPos pos, String url, String title, boolean isPlaying) {
        this.pos = pos;
        this.url = url;
        this.title = title;
        this.isPlaying = isPlaying;
    }

    public static void encode(PacketUpdateVideoScreen packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.url);
        buf.writeUtf(packet.title);
        buf.writeBoolean(packet.isPlaying);
    }

    public static PacketUpdateVideoScreen decode(FriendlyByteBuf buf) {
        return new PacketUpdateVideoScreen(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }

    public static void handle(PacketUpdateVideoScreen packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        BlockPos pos = packet.pos;
        String url = packet.url;
        String title = packet.title;
        boolean isPlaying = packet.isPlaying;
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.handleUpdateVideoScreen(pos, url, title, isPlaying)));
        context.setPacketHandled(true);
    }
}