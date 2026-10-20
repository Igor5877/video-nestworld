package com.nestworld.video.network;

import com.nestworld.video.CinemaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client heartbeat telling the server that this client has an active screen decoder. */
public class PacketScreenWatch {
    private final BlockPos pos;
    private final boolean watching;
    /** The client's clock when it sent this, echoed back in {@link PacketScreenClock}. */
    private final long clientMs;

    public PacketScreenWatch(BlockPos pos, boolean watching) { this(pos, watching, com.nestworld.video.SyncClock.nowMs()); }

    private PacketScreenWatch(BlockPos pos, boolean watching, long clientMs) {
        this.pos = pos.immutable();
        this.watching = watching;
        this.clientMs = clientMs;
    }

    public static void encode(PacketScreenWatch packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeBoolean(packet.watching);
        buf.writeLong(packet.clientMs);
    }

    public static PacketScreenWatch decode(FriendlyByteBuf buf) {
        return new PacketScreenWatch(buf.readBlockPos(), buf.readBoolean(), buf.readLong());
    }

    public static void handle(PacketScreenWatch packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            if (!packet.watching) {
                CinemaMod.removeViewer(player, packet.pos);
                return;
            }
            CinemaMod.updateViewer(player, packet.pos);
            // The pulse: tell this viewer where the timeline is, with the server's clock and its own send time echoed.
            if (player.level().hasChunkAt(packet.pos) && player.level().getBlockEntity(packet.pos) instanceof com.nestworld.video.block.VideoScreenBlockEntity screen
                    && screen.isMaster()) {
                long[] now = screen.timelineNow();
                Networking.INSTANCE.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                        new PacketScreenClock(packet.pos, screen.isPlaying(), now[0], now[1], packet.clientMs));
            }
        });
        context.setPacketHandled(true);
    }
}
