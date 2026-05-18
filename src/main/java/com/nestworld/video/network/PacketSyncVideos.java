package com.nestworld.video.network;

import com.nestworld.video.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketSyncVideos {

    private final String jsonConfig;

    public PacketSyncVideos(String jsonConfig) {
        this.jsonConfig = jsonConfig;
    }

    // "Запаковуємо" наш рядок в байти для відправки
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(this.jsonConfig);
    }

    // "Розпаковуємо" байти назад в об'єкт пакету
    public static PacketSyncVideos decode(FriendlyByteBuf buffer) {
        return new PacketSyncVideos(buffer.readUtf());
    }

    public static void handle(PacketSyncVideos message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        String jsonConfig = message.jsonConfig;
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.handleSyncVideos(jsonConfig)));
        context.setPacketHandled(true);
    }
}