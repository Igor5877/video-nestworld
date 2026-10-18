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

    public PacketScreenWatch(BlockPos pos, boolean watching) {
        this.pos = pos.immutable();
        this.watching = watching;
    }

    public static void encode(PacketScreenWatch packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeBoolean(packet.watching);
    }

    public static PacketScreenWatch decode(FriendlyByteBuf buf) {
        return new PacketScreenWatch(buf.readBlockPos(), buf.readBoolean());
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
        });
        context.setPacketHandled(true);
    }
}
