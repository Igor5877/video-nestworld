package com.nestworld.video.network;

import com.google.gson.Gson;
import com.nestworld.video.block.VideoScreenMenu;
import com.nestworld.video.integration.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public record PacketBrowseRequest(int menuId, int requestId, String action, String query) {
    private static final Map<ServerPlayer, Long> BUSY = new WeakHashMap<>();
    private static final Semaphore REMOTE_SLOTS = new Semaphore(4);
    public void encode(FriendlyByteBuf b) { b.writeInt(menuId); b.writeInt(requestId); b.writeUtf(action,16); b.writeUtf(query,120); }
    public static PacketBrowseRequest decode(FriendlyByteBuf b) { return new PacketBrowseRequest(b.readInt(), b.readInt(), b.readUtf(16), b.readUtf(120)); }
    public static void handle(PacketBrowseRequest msg, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> {
            var player = ctx.getSender();
            if (player == null || player.isSpectator() || !(player.containerMenu instanceof VideoScreenMenu menu)
                    || menu.containerId != msg.menuId || !menu.stillValid(player)
                    || !player.level().mayInteract(player, menu.getBlockEntity().getBlockPos())) return;
            if (!msg.action.equals("search") && !msg.action.equals("load") && !msg.action.equals("seasons")) return;
            long now = System.nanoTime();
            if (BUSY.getOrDefault(player, 0L) > now) {
                reply(player, msg, true, "Попередній запит ще виконується. Спробуй за кілька секунд."); return;
            }
            if (!REMOTE_SLOTS.tryAcquire()) {
                reply(player, msg, true, "Сервер уже обробляє запити до каталогу. Спробуй за кілька секунд."); return;
            }
            BUSY.put(player, now + 60_000_000_000L);
            var server = player.getServer();
            CompletableFuture<?> work;
            try {
                if (msg.action.equals("search")) work = HikkaCatalogClient.searchTitles(msg.query);
                else if (msg.action.equals("seasons")) work = HikkaCatalogClient.seasons(msg.query);
                else work = HikkaCatalogClient.detail(msg.query).thenCompose(title ->
                    AniuaApiClient.loadEpisodes(title.slug(), VideoSourceResolver.load(server.getServerDirectory().toPath()))
                        .thenApply(category -> { category.setDetails(title.title(), title.poster(), title.seasonLabel()); category.setRating(title.rating()); return category; }));
            } catch (RuntimeException exception) {
                REMOTE_SLOTS.release();
                BUSY.put(player, now + 500_000_000L);
                reply(player, msg, true, exception.getMessage() == null ? "Не вдалося створити запит." : exception.getMessage());
                return;
            }
            work.orTimeout(50, java.util.concurrent.TimeUnit.SECONDS).whenComplete((result, error) -> {
                REMOTE_SLOTS.release();
                server.execute(() -> {
                BUSY.put(player, now + 500_000_000L);
                // A closed/replaced menu must neither receive a response nor import a stale selection.
                if (player.containerMenu != menu || !menu.stillValid(player)) return;
                if (error != null) {
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    String detail = cause.getMessage();
                    reply(player, msg, true, detail == null ? "Сервіс не відповідає. Спробуй ще раз." : detail); return;
                }
                try {
                    if (result instanceof com.nestworld.video.Category category) CatalogueStore.save(server, category);
                    reply(player, msg, false, new Gson().toJson(result));
                } catch (Exception e) { reply(player, msg, true, "Не вдалося зберегти каталог: " + e.getMessage()); }
                });
            });
        });
        ctx.setPacketHandled(true);
    }
    private static void reply(ServerPlayer player, PacketBrowseRequest request, boolean error, String body) {
        if (error && body.length() > 800) body = body.substring(0,800);
        Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketBrowseResult(request.menuId, request.requestId, request.action, error, body));
    }
}
