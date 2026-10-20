package com.nestworld.video.network;

import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** A viewer cancels the announced "next episode in 5 s" (from the HUD key or the menu), without opening the menu. */
public class PacketCancelNext {
    private final BlockPos pos;

    public PacketCancelNext(BlockPos pos) { this.pos = pos.immutable(); }

    public static void encode(PacketCancelNext packet, FriendlyByteBuf buf) { buf.writeBlockPos(packet.pos); }

    public static PacketCancelNext decode(FriendlyByteBuf buf) { return new PacketCancelNext(buf.readBlockPos()); }

    public static void handle(PacketCancelNext packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || player.isSpectator() || !player.level().hasChunkAt(packet.pos)) return;
            if (player.distanceToSqr(packet.pos.getX() + .5, packet.pos.getY() + .5, packet.pos.getZ() + .5) > 64 * 64) return;
            if (player.level().getBlockEntity(packet.pos) instanceof VideoScreenBlockEntity screen && screen.isMaster()
                    && player.level().mayInteract(player, packet.pos)) {
                if (com.nestworld.video.session.SessionGuard.canControl(player, screen)) screen.cancelNext();
                else com.nestworld.video.session.SessionGuard.deny(player, screen);
            }
        });
        context.setPacketHandled(true);
    }
}
