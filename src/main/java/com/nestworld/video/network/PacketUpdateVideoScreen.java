package com.nestworld.video.network;

import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.client.ClientVideoManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketUpdateVideoScreen {

    private final BlockPos pos;
    private final String url;
    private final String title;
    private final boolean isPlaying;

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
        context.enqueueWork(() -> {
            Level world = net.minecraft.client.Minecraft.getInstance().level;
            if (world == null) return;

            // Stop the display immediately — must work even after the block is already removed.
            if (!packet.isPlaying) {
                ClientVideoManager.getInstance().stopPlaying(packet.pos);
                return;
            }

            // For play: the block entity must still exist.
            if (!world.isLoaded(packet.pos)) return;
            BlockEntity blockEntity = world.getBlockEntity(packet.pos);
            if (blockEntity instanceof VideoScreenBlockEntity screen) {
                screen.setVideoUrl(packet.url);
                screen.setVideoTitle(packet.title);
                screen.setPlaying(true);
                ClientVideoManager.getInstance().startPlaying(packet.pos, packet.url);
            }
        });
        context.setPacketHandled(true);
    }
}