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
    final boolean stopped;
    final long positionMs;
    final long serverMs;
    final boolean autoNext, repeat, nextCancelled;
    final String relayUrl;
    final boolean adult;

    public PacketUpdateVideoScreen(BlockPos pos, String url, String title, boolean isPlaying, boolean stopped,
                                   long positionMs, long serverMs,
                                   boolean autoNext, boolean repeat, boolean nextCancelled, String relayUrl, boolean adult) {
        this.pos = pos;
        this.url = url;
        this.title = title;
        this.isPlaying = isPlaying;
        this.stopped = stopped;
        this.positionMs = positionMs;   // below 0 while the screen counts down to a start
        this.serverMs = serverMs;
        this.autoNext = autoNext; this.repeat = repeat; this.nextCancelled = nextCancelled;
        this.relayUrl = relayUrl == null ? "" : relayUrl;
        this.adult = adult;
    }

    public static void encode(PacketUpdateVideoScreen packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.url);
        buf.writeUtf(packet.title);
        buf.writeBoolean(packet.isPlaying);
        buf.writeBoolean(packet.stopped);
        buf.writeLong(packet.positionMs);
        buf.writeLong(packet.serverMs);
        buf.writeBoolean(packet.autoNext); buf.writeBoolean(packet.repeat); buf.writeBoolean(packet.nextCancelled);
        buf.writeUtf(packet.relayUrl, 2048);
        buf.writeBoolean(packet.adult);
    }

    public static PacketUpdateVideoScreen decode(FriendlyByteBuf buf) {
        return new PacketUpdateVideoScreen(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                buf.readBoolean(), buf.readLong(), buf.readLong(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readUtf(2048), buf.readBoolean());
    }

    public static void handle(PacketUpdateVideoScreen packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        BlockPos pos = packet.pos;
        String url = packet.url;
        String title = packet.title;
        boolean isPlaying = packet.isPlaying;
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.handleUpdateVideoScreen(pos, url, title, isPlaying, packet.stopped,
                        packet.positionMs, packet.serverMs, packet.autoNext, packet.repeat, packet.nextCancelled, packet.relayUrl, packet.adult)));
        context.setPacketHandled(true);
    }
}
