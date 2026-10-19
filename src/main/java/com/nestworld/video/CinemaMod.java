package com.nestworld.video;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.nestworld.video.block.ModBlocks;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.command.ReloadVideosCommand;
import com.nestworld.video.command.AniuaCommand;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketSyncVideos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;

@Mod(CinemaMod.MOD_ID)
@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID)
public class CinemaMod {
    public static final String MOD_ID = "nestworldvideo";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ScreenKey, Map<UUID, Viewer>> SCREEN_VIEWERS = new ConcurrentHashMap<>();
    private static final long VIEWER_TIMEOUT_MS = 12_000L;
    public static VideoConfig videoConfig;

    public CinemaMod() {
        LOGGER.info("Nestworld Video Mod has been initialized.");
        
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        
        // Реєструємо блоки, предмети тощо
        ModBlocks.register(modEventBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, NestworldConfig.CLIENT_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.nestworld.video.relay.RelayConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.nestworld.video.content.ContentConfig.SPEC, "nestworldvideo-content.toml");
        
        // Ініціалізуємо нашу мережу
        Networking.registerMessages();
    }

    private record Viewer(String name, long lastSeenMs) {}
    private record ScreenKey(ResourceKey<Level> dimension, BlockPos pos) {}
    public record ViewerSnapshot(int count, List<String> names) {}

    /** Accept a client heartbeat only for a nearby master screen in the same dimension. */
    public static void updateViewer(ServerPlayer player, BlockPos pos) {
        if (!player.level().hasChunkAt(pos)
                || !(player.level().getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)
                || !screen.isMaster()
                || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 256 * 256) {
            removeViewer(player, pos);
            return;
        }
        ScreenKey key = new ScreenKey(player.level().dimension(), pos.immutable());
        SCREEN_VIEWERS.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>())
                .put(player.getUUID(), new Viewer(player.getName().getString(), System.currentTimeMillis()));
    }

    public static void removeViewer(ServerPlayer player, BlockPos pos) {
        ScreenKey key = new ScreenKey(player.level().dimension(), pos.immutable());
        Map<UUID, Viewer> viewers = SCREEN_VIEWERS.get(key);
        if (viewers == null) return;
        viewers.remove(player.getUUID());
        if (viewers.isEmpty()) SCREEN_VIEWERS.remove(key, viewers);
    }

    public static void removeViewerEverywhere(ServerPlayer player) {
        SCREEN_VIEWERS.values().forEach(viewers -> viewers.remove(player.getUUID()));
        SCREEN_VIEWERS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public static boolean isWatching(ServerPlayer player, BlockPos pos) {
        Map<UUID, Viewer> viewers = SCREEN_VIEWERS.get(new ScreenKey(player.level().dimension(), pos.immutable()));
        if (viewers == null) return false;
        Viewer viewer = viewers.get(player.getUUID());
        return viewer != null && System.currentTimeMillis() - viewer.lastSeenMs() <= VIEWER_TIMEOUT_MS;
    }

    public static ViewerSnapshot viewerSnapshot(ServerLevel level, BlockPos pos) {
        ScreenKey key = new ScreenKey(level.dimension(), pos.immutable());
        Map<UUID, Viewer> viewers = SCREEN_VIEWERS.get(key);
        if (viewers == null) return new ViewerSnapshot(0, List.of());
        long now = System.currentTimeMillis();
        viewers.entrySet().removeIf(entry -> now - entry.getValue().lastSeenMs() > VIEWER_TIMEOUT_MS);
        if (viewers.isEmpty()) {
            SCREEN_VIEWERS.remove(key, viewers);
            return new ViewerSnapshot(0, List.of());
        }
        List<String> names = viewers.values().stream().map(Viewer::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return new ViewerSnapshot(viewers.size(), names);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 100 != 0) return;
        long now = System.currentTimeMillis();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) sendAdultAccess(player, false);
        if (com.nestworld.video.relay.RelayManager.running()) {
            Map<ResourceKey<Level>, Map<BlockPos, Integer>> counts = new java.util.HashMap<>();
            for (ScreenKey key : SCREEN_VIEWERS.keySet()) {
                var level = event.getServer().getLevel(key.dimension());
                if (level == null) continue;
                counts.computeIfAbsent(key.dimension(), d -> new java.util.HashMap<>()).put(key.pos(), viewerSnapshot(level, key.pos()).count());
            }
            com.nestworld.video.relay.RelayManager.update(event.getServer(), counts);
        }
        SCREEN_VIEWERS.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(viewer -> now - viewer.getValue().lastSeenMs() > VIEWER_TIMEOUT_MS);
            return entry.getValue().isEmpty();
        });
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SCREEN_VIEWERS.clear();
        com.nestworld.video.relay.RelayManager.stop();
    }

    /** What each client was last told about 18+ access, so permission changes (e.g. in LuckPerms) reach it within seconds. */
    private static final Map<UUID, Boolean> ADULT_ACCESS_SENT = new ConcurrentHashMap<>();

    /** Re-evaluate 18+ access of everybody now (e.g. after the default was changed by a command). */
    public static void refreshAdultAccess(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendAdultAccess(player, false);
    }

    private static void sendAdultAccess(ServerPlayer player, boolean force) {
        boolean allowed = com.nestworld.video.content.AdultAccess.canView(player);
        Boolean previous = ADULT_ACCESS_SENT.put(player.getUUID(), allowed);
        if (force || previous == null || previous != allowed)
            Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new com.nestworld.video.network.PacketAdultAccess(allowed));
    }

    @SubscribeEvent
    public static void onPermissionNodes(net.minecraftforge.server.permission.events.PermissionGatherEvent.Nodes event) {
        com.nestworld.video.content.AdultAccess.registerNodes(event);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ADULT_ACCESS_SENT.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) removeViewerEverywhere(player);
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        com.nestworld.video.relay.RelayManager.start();
        LOGGER.info("NESTWORLD_VIDEO: Server is starting, attempting to load videos.json...");
        File configFile = new File(event.getServer().getServerDirectory(), "config/videos.json");
        if (!configFile.exists()) {
            LOGGER.error("NESTWORLD_VIDEO: videos.json not found in config folder! Please create it.");
            return;
        }
        try {
            String jsonContent = Files.readString(configFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            videoConfig = new Gson().fromJson(jsonContent, VideoConfig.class);
            LOGGER.info("NESTWORLD_VIDEO: Successfully loaded {} categories from videos.json.", videoConfig.getCategories().size());
        } catch (Exception e) {
            LOGGER.error("NESTWORLD_VIDEO: Failed to read or parse videos.json.", e);
        }
    }
    
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ReloadVideosCommand.register(event.getDispatcher());
        AniuaCommand.register(event.getDispatcher());
        com.nestworld.video.command.FilmsCommand.register(event.getDispatcher());
        com.nestworld.video.command.AdultCommand.register(event.getDispatcher());
        LOGGER.info("NESTWORLD_VIDEO: Registered reload command");
    }

    // НОВИЙ МЕТОД: викликається, коли гравець заходить на сервер
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer joined) sendAdultAccess(joined, true);
        if (videoConfig != null && event.getEntity() instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            LOGGER.info("NESTWORLD_VIDEO: Player {} joined. Sending video config...", player.getName().getString());

            // Відправляємо пакет з конфігурацією конкретному гравцю
            String json = new Gson().toJson(videoConfig);
            Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new PacketSyncVideos(json));
        }
    }
}
