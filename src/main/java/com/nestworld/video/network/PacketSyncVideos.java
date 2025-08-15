package com.nestworld.video.network;

import com.mojang.logging.LogUtils;
import com.nestworld.video.VideoConfig;
import com.nestworld.video.client.ClientData;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import com.google.gson.Gson;
import com.nestworld.video.VideoConfig;
import com.nestworld.video.client.ClientData;

import java.util.function.Supplier;

public class PacketSyncVideos {

    private final String jsonConfig;
    private static final Logger LOGGER = LogUtils.getLogger();

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

    // Цей код виконається на КЛІЄНТІ, коли він отримає пакет
    public static void handle(PacketSyncVideos message, Supplier<NetworkEvent.Context> contextSupplier) {
    NetworkEvent.Context context = contextSupplier.get();
    context.enqueueWork(() -> {
        // --- ЛОГІКА НА СТОРОНІ КЛІЄНТА ---
        LOGGER.info("CLIENT: Received video config from server! Parsing...");
        try {
            // Розпарсюємо JSON і зберігаємо в нашому статичному класі
            VideoConfig config = new Gson().fromJson(message.jsonConfig, VideoConfig.class);
            ClientData.setVideoConfig(config);
            LOGGER.info("CLIENT: Successfully parsed and stored video config. {} categories loaded.", config.getCategories().size());
        } catch (Exception e) {
            LOGGER.error("CLIENT: Failed to parse video config from server.", e);
        }
    });
    context.setPacketHandled(true);
}
}