package com.nestworld.video.network;

import com.nestworld.video.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record PacketBrowseResult(int menuId, int requestId, String action, boolean error, String body) {
    public void encode(FriendlyByteBuf b) { b.writeInt(menuId); b.writeInt(requestId); b.writeUtf(action,16); b.writeBoolean(error); b.writeUtf(body, PacketSyncVideos.MAX_LENGTH); }
    public static PacketBrowseResult decode(FriendlyByteBuf b) { return new PacketBrowseResult(b.readInt(), b.readInt(), b.readUtf(16), b.readBoolean(), b.readUtf(PacketSyncVideos.MAX_LENGTH)); }
    public static void handle(PacketBrowseResult msg, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleBrowse(msg)));
        ctx.setPacketHandled(true);
    }
}
