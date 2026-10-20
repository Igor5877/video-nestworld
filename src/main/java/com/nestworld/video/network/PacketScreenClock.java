package com.nestworld.video.network;

import com.nestworld.video.client.ClientPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * The server's answer to a viewer's heartbeat (every 5 s): where the shared timeline is, the server's clock, and the
 * client's send time echoed back, so the client can measure the round trip and estimate the server's clock.
 */
public class PacketScreenClock {
    private final BlockPos pos;
    private final boolean playing;
    private final long positionMs, serverMs, clientSentMs;

    public PacketScreenClock(BlockPos pos, boolean playing, long positionMs, long serverMs, long clientSentMs) {
        this.pos = pos.immutable();
        this.playing = playing;
        this.positionMs = positionMs; this.serverMs = serverMs; this.clientSentMs = clientSentMs;
    }

    public static void encode(PacketScreenClock packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeBoolean(packet.playing);
        buf.writeLong(packet.positionMs);
        buf.writeLong(packet.serverMs);
        buf.writeLong(packet.clientSentMs);
    }

    public static PacketScreenClock decode(FriendlyByteBuf buf) {
        return new PacketScreenClock(buf.readBlockPos(), buf.readBoolean(), buf.readLong(), buf.readLong(), buf.readLong());
    }

    public static void handle(PacketScreenClock packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                ClientPacketHandler.handleScreenClock(packet.pos, packet.playing, packet.positionMs, packet.serverMs, packet.clientSentMs)));
        context.setPacketHandled(true);
    }
}
