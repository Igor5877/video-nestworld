package com.nestworld.video.network;

import com.nestworld.video.client.ClientData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Tells a client whether its player may watch 18+ videos (sent at login and whenever the permission changes). */
public class PacketAdultAccess {
    private final boolean allowed;

    public PacketAdultAccess(boolean allowed) { this.allowed = allowed; }

    public static void encode(PacketAdultAccess packet, FriendlyByteBuf buf) { buf.writeBoolean(packet.allowed); }

    public static PacketAdultAccess decode(FriendlyByteBuf buf) { return new PacketAdultAccess(buf.readBoolean()); }

    public static void handle(PacketAdultAccess packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        boolean allowed = packet.allowed;
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientData.setAdultAllowed(allowed)));
        context.setPacketHandled(true);
    }
}
