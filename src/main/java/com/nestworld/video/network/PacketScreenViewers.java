package com.nestworld.video.network;

import com.nestworld.video.client.ClientPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** Server response for the viewer counter shown in the screen GUI. */
public class PacketScreenViewers {
    private final BlockPos pos;
    private final int count;
    private final List<String> names;

    public PacketScreenViewers(BlockPos pos, int count, List<String> names) {
        this.pos = pos.immutable();
        this.count = Math.max(0, count);
        this.names = names == null ? List.of() : List.copyOf(names);
    }

    public static void encode(PacketScreenViewers packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeVarInt(packet.count);
        buf.writeVarInt(Math.min(packet.names.size(), 128));
        packet.names.stream().limit(128).forEach(name -> buf.writeUtf(name, 64));
    }

    public static PacketScreenViewers decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int count = buf.readVarInt();
        int size = Math.max(0, Math.min(128, buf.readVarInt()));
        java.util.ArrayList<String> names = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) names.add(buf.readUtf(64));
        return new PacketScreenViewers(pos, count, names);
    }

    public static void handle(PacketScreenViewers packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.handleScreenViewers(packet.pos, packet.count, packet.names)));
        context.setPacketHandled(true);
    }
}
