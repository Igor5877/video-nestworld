package com.nestworld.video.integration;

import com.google.gson.Gson;
import com.nestworld.video.*;
import com.nestworld.video.network.*;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.network.PacketDistributor;
import java.nio.file.*;
import java.util.ArrayList;

public final class CatalogueStore {
    private CatalogueStore() {}
    /** Called on the server thread, so concurrent imports cannot overwrite each other. */
    public static void save(MinecraftServer server, Category category) throws java.io.IOException {
        var categories = new ArrayList<Category>();
        if (CinemaMod.videoConfig != null && CinemaMod.videoConfig.getCategories() != null)
            categories.addAll(CinemaMod.videoConfig.getCategories());
        categories.removeIf(c -> c != null && java.util.Objects.equals(c.getName(), category.getName()));
        categories.add(category);
        var updated = new VideoConfig(categories);
        String json = new Gson().toJson(updated);
        if (PacketSyncVideos.compressedSize(json) < 0) throw new java.io.IOException("Каталог завеликий");
        Path config = server.getServerDirectory().toPath().resolve("config/videos.json");
        Files.createDirectories(config.getParent());
        Path temp = Files.createTempFile(config.getParent(), "videos-", ".tmp");
        try {
            Files.writeString(temp, json);
            try { Files.move(temp, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, config, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
        CinemaMod.videoConfig = updated;
        for (var player : server.getPlayerList().getPlayers())
            PacketSyncVideos.send(player, json);
    }
}
