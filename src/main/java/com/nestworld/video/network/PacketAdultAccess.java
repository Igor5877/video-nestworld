package com.nestworld.video.network;

import com.nestworld.video.client.ClientData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Tells a client whether its player may watch 18+ videos and whether they may control every screen (operator or
 * {@code nestworldvideo.control}); sent at login and whenever a permission changes. The server checks both anyway.
 */
public class PacketAdultAccess {
    private final boolean allowed, control;

    public PacketAdultAccess(boolean allowed, boolean control) { this.allowed = allowed; this.control = control; }

    public static void encode(PacketAdultAccess packet, FriendlyByteBuf buf) { buf.writeBoolean(packet.allowed); buf.writeBoolean(packet.control); }

    public static PacketAdultAccess decode(FriendlyByteBuf buf) { return new PacketAdultAccess(buf.readBoolean(), buf.readBoolean()); }

    public static void handle(PacketAdultAccess packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        boolean allowed = packet.allowed, control = packet.control;
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> { ClientData.setAdultAllowed(allowed); ClientData.setControlAllowed(control); }));
        context.setPacketHandled(true);
    }
}
