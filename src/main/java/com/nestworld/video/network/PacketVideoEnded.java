package com.nestworld.video.network;

import com.nestworld.video.NestworldConfig;
import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client report that its decoder played the screen's current video to the end. */
public class PacketVideoEnded {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private final BlockPos pos;
    private final String url;
    /** The length of the video as this client's decoder knows it, 0 when unknown. */
    private final long durationMs;

    public PacketVideoEnded(BlockPos pos, String url, long durationMs) {
        this.pos = pos.immutable();
        this.url = url;
        this.durationMs = durationMs;
    }

    public static void encode(PacketVideoEnded packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.url, 4096);
        buf.writeLong(packet.durationMs);
    }

    public static PacketVideoEnded decode(FriendlyByteBuf buf) {
        return new PacketVideoEnded(buf.readBlockPos(), buf.readUtf(4096), buf.readLong());
    }

    public static void handle(PacketVideoEnded packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || player.isSpectator() || !player.level().hasChunkAt(packet.pos)) return;
            int max = NestworldConfig.MAX_STREAM_DISTANCE.get();
            if (player.distanceToSqr(packet.pos.getX() + .5, packet.pos.getY() + .5, packet.pos.getZ() + .5)
                    > (double) max * max) return;
            if (player.level().getBlockEntity(packet.pos) instanceof VideoScreenBlockEntity screen && screen.isMaster()) {
                screen.noteDuration(player.getUUID(), packet.url, packet.durationMs);
                // A decoder that hit the end of a broken-off stream is not the end of the video for everyone.
                if (screen.atEnd(packet.url, packet.durationMs)) screen.playNext(packet.url);
                else LOGGER.debug("Ignored an early end report for {} from {}", packet.url, player.getGameProfile().getName());
            }
        });
        context.setPacketHandled(true);
    }
}
