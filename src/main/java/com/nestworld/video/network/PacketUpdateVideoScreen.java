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
    final long serverGameTime;
    final boolean autoNext, repeat, nextCancelled;

    public PacketUpdateVideoScreen(BlockPos pos, String url, String title, boolean isPlaying, boolean stopped,
                                   long positionMs, long serverGameTime,
                                   boolean autoNext, boolean repeat, boolean nextCancelled) {
        this.pos = pos;
        this.url = url;
        this.title = title;
        this.isPlaying = isPlaying;
        this.stopped = stopped;
        this.positionMs = Math.max(0, positionMs);
        this.serverGameTime = serverGameTime;
        this.autoNext = autoNext; this.repeat = repeat; this.nextCancelled = nextCancelled;
    }

    public static void encode(PacketUpdateVideoScreen packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.url);
        buf.writeUtf(packet.title);
        buf.writeBoolean(packet.isPlaying);
        buf.writeBoolean(packet.stopped);
        buf.writeLong(packet.positionMs);
        buf.writeLong(packet.serverGameTime);
        buf.writeBoolean(packet.autoNext); buf.writeBoolean(packet.repeat); buf.writeBoolean(packet.nextCancelled);
    }

    public static PacketUpdateVideoScreen decode(FriendlyByteBuf buf) {
        return new PacketUpdateVideoScreen(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                buf.readBoolean(), buf.readLong(), buf.readLong(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
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
                        packet.positionMs, packet.serverGameTime, packet.autoNext, packet.repeat, packet.nextCancelled)));
        context.setPacketHandled(true);
    }
}
