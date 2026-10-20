package com.nestworld.video.network;

import com.nestworld.video.CinemaMod;
import com.nestworld.video.Video;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.block.VideoScreenMenu;
import com.nestworld.video.block.ScreenRemoteItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import java.util.function.Supplier;

public class PacketControlVideo {
    private final BlockPos blockPos;
    private final String action, videoUrl, videoTitle;
    private final long value;
    public PacketControlVideo(BlockPos pos, String action) { this(pos, action, "", "", 0); }
    public PacketControlVideo(BlockPos pos, String action, String url, String title) {
        this(pos, action, url, title, 0);
    }
    public PacketControlVideo(BlockPos pos, String action, long value) {
        this(pos, action, "", "", value);
    }
    private PacketControlVideo(BlockPos pos, String action, String url, String title, long value) {
        blockPos = pos; this.action = action; videoUrl = url; videoTitle = title; this.value = value;
    }
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(blockPos); buffer.writeUtf(action, 32);
        buffer.writeUtf(videoUrl, 4096); buffer.writeUtf(videoTitle, 512); buffer.writeLong(value);
    }
    public static PacketControlVideo decode(FriendlyByteBuf buffer) {
        return new PacketControlVideo(buffer.readBlockPos(), buffer.readUtf(32), buffer.readUtf(4096), buffer.readUtf(512), buffer.readLong());
    }
    private static void manageAccess(PacketControlVideo message, net.minecraft.server.level.ServerPlayer player, VideoScreenBlockEntity screen) {
        switch (message.action) {
            case "access_mode" -> {
                var values = com.nestworld.video.session.ScreenAccess.values();
                if (message.value < 0 || message.value >= values.length) return;
                if (screen.getScreenOwner() == null) screen.setOwner(player);   // limiting an ownerless screen makes it yours
                screen.setAccess(values[(int) message.value]);
            }
            case "trust_add" -> {
                String name = message.videoUrl.trim();
                if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                    player.displayClientMessage(Component.literal("Некоректний нік гравця."), true);
                    return;
                }
                var online = player.server.getPlayerList().getPlayerByName(name);
                var profile = online != null ? java.util.Optional.of(online.getGameProfile())
                        : player.server.getProfileCache() == null ? java.util.Optional.<com.mojang.authlib.GameProfile>empty() : player.server.getProfileCache().get(name);
                if (profile.isEmpty()) {
                    player.displayClientMessage(Component.literal("Гравця " + name + " не знайдено: він має хоч раз зайти на сервер."), true);
                    return;
                }
                if (screen.getScreenOwner() == null) screen.setOwner(player);
                if (!screen.trust(profile.get().getId(), profile.get().getName()))
                    player.displayClientMessage(Component.literal("У списку вже " + VideoScreenBlockEntity.MAX_TRUSTED + " гравці: це межа."), true);
            }
            case "trust_remove" -> {
                try { screen.untrust(java.util.UUID.fromString(message.videoUrl)); }
                catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public static void handle(PacketControlVideo message, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || player.isSpectator() || !player.level().hasChunkAt(message.blockPos)) return;
            if (!(player.level().getBlockEntity(message.blockPos) instanceof VideoScreenBlockEntity raw)) return;
            boolean remote = player.containerMenu instanceof VideoScreenMenu remoteMenu
                    && remoteMenu.isRemoteControl() && remoteMenu.getBlockEntity() == raw
                    && remoteMenu.stillValid(player) && ScreenRemoteItem.playerHasRemoteFor(player, message.blockPos);
            if ((!remote && player.distanceToSqr(message.blockPos.getX() + .5, message.blockPos.getY() + .5,
                    message.blockPos.getZ() + .5) > 64) || !player.level().mayInteract(player, message.blockPos)) return;
            if (message.action.equals("open")) { NetworkHooks.openScreen(player, raw, message.blockPos); return; }
            if (!(player.containerMenu instanceof VideoScreenMenu menu) || menu.getBlockEntity() != raw || !menu.stillValid(player)) return;
            var screen = raw.controller();
            if (message.action.equals("viewers")) {
                if (screen == null) return;
                var snapshot = CinemaMod.viewerSnapshot(player.serverLevel(), screen.getBlockPos());
                Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                        new PacketScreenViewers(screen.getBlockPos(), snapshot.count(), snapshot.names()));
                return;
            }
            // Playback controls and taking the screen apart need the screen's access and, while a video plays, its session.
            if (screen != null && java.util.Set.of("play", "pause", "stop", "auto_next", "repeat", "seek_by", "select", "session_lock",
                    "session_release", "reset_layout").contains(message.action) && !com.nestworld.video.session.SessionGuard.canControl(player, screen)) {
                com.nestworld.video.session.SessionGuard.deny(player, screen);
                return;
            }
            // Who controls the screen: its owner, operators and players with nestworldvideo.control.
            if (java.util.Set.of("access_mode", "trust_add", "trust_remove").contains(message.action)) {
                if (screen == null) return;
                if (!com.nestworld.video.session.SessionGuard.canManage(player, screen)) {
                    player.displayClientMessage(Component.literal("Доступ до екрана змінює лише його власник або оператор."), true);
                    return;
                }
                manageAccess(message, player, screen);
                return;
            }
            if (message.action.equals("claim_screen")) {
                // A screen from before owners existed: whoever may build here and control it takes it.
                if (screen != null && player.mayBuild() && (screen.getScreenOwner() == null
                        ? com.nestworld.video.session.SessionGuard.canControl(player, screen) : com.nestworld.video.session.SessionGuard.isAdmin(player)))
                    screen.setOwner(player);
                return;
            }
            if (message.action.equals("assemble")) {
                // Building over other people's screens (adding blocks to them) needs control of each of them.
                var layout = com.nestworld.video.block.ScreenLayout.detect(player.level(), message.blockPos);
                if (layout.valid()) for (var pos : layout.tiles())
                    if (player.level().getBlockEntity(pos) instanceof VideoScreenBlockEntity tile && tile.controller() != null
                            && !com.nestworld.video.session.SessionGuard.canControl(player, tile.controller())) {
                        com.nestworld.video.session.SessionGuard.deny(player, tile.controller());
                        return;
                    }
            }
            switch (message.action) {
                case "session_lock" -> { if (screen != null) screen.setSessionProtection(message.value != 0); }
                case "session_release" -> { if (screen != null) screen.setSessionOwner(null); }
                case "assemble" -> {
                    if (!player.mayBuild()) return;
                    var result = raw.assemble();
                    player.displayClientMessage(result.valid()
                            ? Component.translatable("gui.nestworldvideo.assembled", result.cols(), result.rows())
                            : Component.translatable("gui.nestworldvideo.error." + result.error()), false);
                }
                case "reset_layout" -> { if (player.mayBuild()) raw.resetLayout(); }
                case "play" -> {
                    if (screen != null) {
                        if (com.nestworld.video.content.AdultAccess.isAdultUrl(screen.getCurrentVideoUrl()) && !com.nestworld.video.content.AdultAccess.canView(player)) {
                            player.displayClientMessage(Component.literal("Це відео 18+: у вас немає права його запускати."), true);
                            return;
                        }
                        screen.play();
                        com.nestworld.video.session.SessionGuard.claim(player, screen);
                    }
                }
                case "pause" -> {
                    if (screen != null) {
                        if (message.value >= 0) screen.pauseAt(message.value);
                        else screen.pause();
                    }
                }
                case "stop" -> {
                    if (screen != null) screen.stopKeepingPosition(message.value);
                }
                case "auto_next" -> { if (screen != null) screen.setAutoNext(message.value != 0); }
                case "repeat" -> { if (screen != null) screen.setRepeat(message.value != 0); }
                case "seek_by" -> { if (screen != null) screen.seekBy(message.value, remote ? player : null); }
                case "select" -> {
                    if (message.videoUrl.isBlank()) return;
                    if (com.nestworld.video.content.AdultAccess.isAdultUrl(message.videoUrl) && !com.nestworld.video.content.AdultAccess.canView(player)) {
                        player.displayClientMessage(Component.literal("Це відео 18+: у вас немає права його запускати."), true);
                        return;
                    }
                    if (screen == null || CinemaMod.videoConfig == null || CinemaMod.videoConfig.getCategories() == null) return;
                    // Only videos from the server's catalogue can be played on other clients.
                    for (var category : CinemaMod.videoConfig.getCategories()) {
                        if (category == null || category.getVideos() == null) continue;
                        for (Video video : category.getVideos()) if (video != null && message.videoUrl.equals(video.getUrl())) {
                            screen.setVideo(video);
                            com.nestworld.video.session.SessionGuard.claim(player, screen);
                            if (remote) screen.syncToClient(player);
                            return;
                        }
                    }
                }
            }
            if (remote && screen != null && !message.action.equals("seek_by")) screen.syncToClient(player);
        });
        context.setPacketHandled(true);
    }
}
