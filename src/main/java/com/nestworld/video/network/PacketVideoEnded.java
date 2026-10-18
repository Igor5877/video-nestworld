package com.nestworld.video.network;

import com.nestworld.video.NestworldConfig;
import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client report that its decoder played the screen's current video to the end. */
public class PacketVideoEnded {
    private final BlockPos pos;
    private final String url;

    public PacketVideoEnded(BlockPos pos, String url) {
        this.pos = pos.immutable();
        this.url = url;
    }

    public static void encode(PacketVideoEnded packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.url, 4096);
    }

    public static PacketVideoEnded decode(FriendlyByteBuf buf) {
        return new PacketVideoEnded(buf.readBlockPos(), buf.readUtf(4096));
    }

    public static void handle(PacketVideoEnded packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || player.isSpectator() || !player.level().hasChunkAt(packet.pos)) return;
            int max = NestworldConfig.MAX_STREAM_DISTANCE.get();
            if (player.distanceToSqr(packet.pos.getX() + .5, packet.pos.getY() + .5, packet.pos.getZ() + .5)
                    > (double) max * max) return;
            if (player.level().getBlockEntity(packet.pos) instanceof VideoScreenBlockEntity screen && screen.isMaster())
                screen.playNext(packet.url);
        });
        context.setPacketHandled(true);
    }
}
