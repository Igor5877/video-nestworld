package com.nestworld.video.client;

import com.nestworld.video.client.util.Display;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketScreenWatch;
import com.nestworld.video.network.PacketVideoEnded;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientVideoManager {

    private static final ClientVideoManager INSTANCE = new ClientVideoManager();
    private final Map<BlockPos, Display> activeDisplays = new ConcurrentHashMap<>();
    /** Screens that the server says should be playing, including displays outside the distance limit. */
    private final Map<BlockPos, String> desiredScreens = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> lastViewerReports = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> lastHistoryRecords = new ConcurrentHashMap<>();
    /** Screens for which an end-of-video report was already sent for the url they are playing. */
    /** Per screen: when the decoder was last found out of step, and when it was last pulled back. */
    private final Map<BlockPos, long[]> driftState = new ConcurrentHashMap<>();
    /** Screens whose relay did not work for this client: they read the video host directly until the given time (ms). */
    private final Map<BlockPos, Long> relayFailedUntil = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> relayStartedNanos = new ConcurrentHashMap<>();
    private final Map<BlockPos, String> lastEndReports = new ConcurrentHashMap<>();
    /** Announcement of the next episode, shown a few seconds before the current one ends. */
    public record NextUp(BlockPos pos, int seconds, String title) {}
    public static final long NEXT_UP_MS = 5_000;
    private volatile NextUp nextUp;
    public NextUp getNextUp() { return nextUp; }
    public NextUp getNextUp(BlockPos pos) { var n = nextUp; return n != null && n.pos().equals(pos) ? n : null; }

    private ClientVideoManager() {}

    public static ClientVideoManager getInstance() {
        return INSTANCE;
    }

    /** Players whose screen was just rebuilt (resized): kept open for a moment for the new screen to take over. */
    private record Orphan(Display display, long sinceMs) {}
    private final Map<BlockPos, Orphan> orphans = new ConcurrentHashMap<>();

    /** The screen at {@code pos} is no longer the one playing (it was merged into a bigger one or split off): keep its player briefly. */
    private void orphan(BlockPos pos) {
        Display display = activeDisplays.get(pos);
        if (display == null) return;
        reportViewer(pos, false);
        lastEndReports.remove(pos);
        driftState.remove(pos);
        acoustics.forget(pos);
        glow.forget(pos);
        activeDisplays.remove(pos);
        Orphan old = orphans.put(pos, new Orphan(display, System.currentTimeMillis()));
        if (old != null && old.display() != display) old.display().release();
    }

    /** A player of {@code url} left by a rebuilt screen, if there is one: the new screen continues with it, without reopening. */
    private Display adopt(BlockPos pos, String url) {
        for (var entry : orphans.entrySet()) {
            if (!entry.getValue().display().getUrl().equals(url) || !orphans.remove(entry.getKey(), entry.getValue())) continue;
            Display display = entry.getValue().display();
            display.setTimeline(() -> timelineAt(pos));
            com.mojang.logging.LogUtils.getLogger().info("Nestworld Video: the resized screen at {} continues the video of {} without reopening it", pos, entry.getKey());
            return display;
        }
        return null;
    }

    private static long timelineAt(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(pos) instanceof VideoScreenBlockEntity s ? s.getTimelinePositionMs() : Long.MIN_VALUE;
    }

    public void startPlaying(BlockPos pos, String url) {
        // Stop any existing video at this position first
        stopPlaying(pos);

        if (url == null || url.isEmpty()) {
            return;
        }
        Display adopted = adopt(pos, url);
        if (adopted != null) {
            activeDisplays.put(pos, adopted);
            return;
        }

        try {
            String source = sourceFor(pos, url);
            if (!source.equals(url)) {
                com.mojang.logging.LogUtils.getLogger().info("Nestworld Video: reading the stream of the screen at {} through the server relay", pos);
                relayStartedNanos.put(pos, System.nanoTime());
            } else relayStartedNanos.remove(pos);
            if (!com.nestworld.video.security.UrlSafety.clientMayOpen(source, serverHost())) {
                com.mojang.logging.LogUtils.getLogger().warn("Nestworld Video: refused to open an address that is not a public http(s) URL for the screen at {}", pos);
                return;
            }
            Display display = Display.create(url, source, qualityCaps.getOrDefault(pos, 0));
            display.syncPosition(screenPosition(pos));
            // A start or a jump waits on the first frame for the shared timeline instead of starting early and jumping again.
            display.setTimeline(() -> timelineAt(pos));
            activeDisplays.put(pos, display);
            openedAtMs.put(pos, System.currentTimeMillis());
        } catch (RuntimeException exception) {
            com.mojang.logging.LogUtils.getLogger().warn("Unable to start video at {}", pos, exception);
        }
    }

    private final com.nestworld.video.client.acoustics.ScreenAcoustics acoustics = new com.nestworld.video.client.acoustics.ScreenAcoustics();
    private final com.nestworld.video.client.glow.ScreenGlow glow = new com.nestworld.video.client.glow.ScreenGlow();
    public com.nestworld.video.client.glow.ScreenGlow getGlow() { return glow; }

    public com.nestworld.video.client.acoustics.ScreenAcoustics getAcoustics() { return acoustics; }

    public void stopPlaying(BlockPos pos) {
        reportViewer(pos, false);
        lastEndReports.remove(pos);
        driftState.remove(pos);
        acoustics.forget(pos);
        glow.forget(pos);
        Display display = activeDisplays.remove(pos);
        if (display != null) {
            display.release();
        }
    }
    
    public void setPlaying(BlockPos pos, boolean playing) {
        Display display = activeDisplays.get(pos);
        if (display != null) {
            display.setPlaying(playing);
        }
    }

    /** Reopen every active stream (e.g. after a quality change); the tick restarts playing screens in sync. */
    public void restartAll() {
        for (BlockPos pos : activeDisplays.keySet()) stopPlaying(pos);
    }

    public java.util.Collection<Display> displays() { return activeDisplays.values(); }

    /** Screens whose stream this client could not keep up with: the video height it plays them at for the rest of the session. */
    private final Map<BlockPos, Integer> qualityCaps = new ConcurrentHashMap<>();

    /** The connection or the decoder could not keep up: reopen the stream one quality step lower, at the shared timeline. */
    private void lowerQuality(BlockPos pos, Display display, int height) {
        qualityCaps.put(pos, height);
        com.mojang.logging.LogUtils.getLogger().info("Nestworld Video: the screen at {} plays at {}p from now on: the video kept stopping to load", pos, height);
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "Nestworld Video: відео не встигало завантажуватися — якість знижено до " + height + "p"), true);
        stopPlaying(pos);   // the screen is reconciled on the next tick and opens again with the cap
    }

    public Display getDisplay(BlockPos pos) {
        return activeDisplays.get(pos);
    }

    public long getPlaybackTime(BlockPos pos) {
        Display display = activeDisplays.get(pos);
        return display == null ? -1 : display.getPlaybackTime();
    }

    /** Record a loaded screen without creating a decoder from the render callback. */
    public void observe(VideoScreenBlockEntity screen) {
        BlockPos pos = screen.getBlockPos();
        if (!screen.isMaster() || screen.isStopped() || screen.getCurrentVideoUrl().isBlank())
            desiredScreens.remove(pos);
        else
            desiredScreens.put(pos, screen.getCurrentVideoUrl());
    }

    public void syncPosition(BlockPos pos, long positionMs) {
        Display display = activeDisplays.get(pos);
        if (display != null) display.syncPosition(positionMs);
    }

    private void reportViewer(BlockPos pos, boolean watching) {
        if (watching) {
            Display display = activeDisplays.get(pos);
            if (Minecraft.getInstance().getConnection() != null)
                Networking.INSTANCE.sendToServer(display == null ? new PacketScreenWatch(pos, true)
                        : new PacketScreenWatch(pos, true, display.getUrl(), Math.max(0, display.getDuration())));
            lastViewerReports.put(pos, 0L);
        } else if (lastViewerReports.remove(pos) != null) {
            if (Minecraft.getInstance().getConnection() != null)
                Networking.INSTANCE.sendToServer(new PacketScreenWatch(pos, false));
        }
    }

    /** What to actually open for the video of the screen at {@code pos}: the server's relay when it offers one, else the video's own URL. */
    private String sourceFor(BlockPos pos, String url) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)) return url;
        String relay = screen.getRelayUrl();
        Long failedUntil = relayFailedUntil.get(pos);
        if (relay == null || relay.isBlank() || (failedUntil != null && System.currentTimeMillis() < failedUntil)) return url;
        return relay.replace(com.nestworld.video.relay.RelayManager.HOST_PLACEHOLDER, serverHost());
    }

    /** Host the player joined the server through (the relay listens on the same machine unless public_host says otherwise). */
    private static String serverHost() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null && connection.getConnection().getRemoteAddress() instanceof java.net.InetSocketAddress address) {
            String host = address.getHostString();
            return host.contains(":") ? "[" + host + "]" : host;
        }
        return "127.0.0.1";
    }

    private long screenPosition(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)
            return screen.getTimelinePositionMs() >= 0 ? screen.getTimelinePositionMs() : screen.getPlaybackPositionMs();
        return 0;
    }

    public void reconcile(VideoScreenBlockEntity screen) {
        BlockPos pos = screen.getBlockPos();
        Display existing = activeDisplays.get(pos);
        if (!screen.isMaster() || screen.isStopped() || screen.getCurrentVideoUrl().isBlank()) {
            desiredScreens.remove(pos);
            // No longer the controller, or a tile split off a resized screen: its player may be taken over by the new screen.
            if (existing != null && (!screen.isMaster() || screen.getCurrentVideoUrl().isBlank())) orphan(pos);
            else if (existing != null) stopPlaying(pos);
            return;
        }
        if (screen.isAdult() && !ClientData.isAdultAllowed()) {
            // 18+ video and this player may not watch it: nothing is decoded or played, the screen shows a placeholder.
            desiredScreens.remove(pos);
            if (existing != null) stopPlaying(pos);
            return;
        }
        desiredScreens.put(pos, screen.getCurrentVideoUrl());
        if (existing != null && (!existing.getUrl().equals(screen.getCurrentVideoUrl())
                || !existing.getSourceUrl().equals(sourceFor(pos, screen.getCurrentVideoUrl())))) {
            stopPlaying(pos); existing = null;      // another video, or the server switched the screen to/from its relay
        }
        if (existing == null && screen.isPlaying() && streamSlotAvailable(screen)) {
            startPlaying(pos, screen.getCurrentVideoUrl());
            existing = activeDisplays.get(pos);
        }
        if (existing != null) existing.setPlaying(screen.isPlaying());
    }

    private boolean streamSlotAvailable(VideoScreenBlockEntity screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        int maxDistance = NestworldConfig.MAX_STREAM_DISTANCE.get();
        if (mc.player.distanceToSqr(screen.getBlockPos().getX() + .5,
                screen.getBlockPos().getY() + .5, screen.getBlockPos().getZ() + .5)
                > (double) maxDistance * maxDistance) return false;
        return activeDisplays.size() < NestworldConfig.MAX_ACTIVE_STREAMS.get() && chosenScreens.contains(screen.getBlockPos());
    }

    // ---- which screens play: the looked-at and nearest ones; the others keep their last picture ---------------------

    /** Screens allowed to have a decoder now (see {@link #choosePlayingScreens}). */
    private final java.util.Set<BlockPos> chosenScreens = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Last pictures of screens whose decoder was closed for the others. */
    private final Map<BlockPos, com.nestworld.video.client.util.StillDisplay> stills = new ConcurrentHashMap<>();
    /** When each decoder was opened: one keeps playing at least a few seconds before it may give way. */
    private final Map<BlockPos, Long> openedAtMs = new ConcurrentHashMap<>();

    /** A playing screen that waits for its turn (others are closer or looked at): not loading, just not decoded here. */
    public boolean waitingForTurn(BlockPos pos) { return desiredScreens.containsKey(pos) && !chosenScreens.contains(pos) && !activeDisplays.containsKey(pos); }

    /** The last picture of the screen at {@code pos} (also while its decoder opens again), or null. */
    public Display still(BlockPos pos) { return stills.get(pos); }

    /**
     * Picks the screens that may decode: the {@code decode_screens} best by distance, where looking at one counts as if it
     * were three times closer and a screen already playing as if a third closer (no flapping between two similar ones).
     */
    private void choosePlayingScreens(Minecraft mc) {
        var candidates = new java.util.HashMap<BlockPos, Double>();
        var look = mc.player.getViewVector(1.0F);
        double ex = mc.player.getX(), ey = mc.player.getEyeY(), ez = mc.player.getZ();
        int maxDistance = NestworldConfig.MAX_STREAM_DISTANCE.get();
        java.util.Set<BlockPos> positions = new java.util.HashSet<>(desiredScreens.keySet());
        positions.addAll(activeDisplays.keySet());
        for (BlockPos pos : positions) {
            if (!(mc.level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen) || !screen.isMaster() || screen.isStopped()) continue;
            if (!screen.isPlaying() && !activeDisplays.containsKey(pos)) continue;   // paused without a decoder: nothing to play
            var b = screen.getRenderBoundingBox();
            double dx = Math.max(b.minX - ex, Math.max(0, ex - b.maxX)), dy = Math.max(b.minY - ey, Math.max(0, ey - b.maxY)), dz = Math.max(b.minZ - ez, Math.max(0, ez - b.maxZ));
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > maxDistance) continue;
            double cx = (b.minX + b.maxX) / 2 - ex, cy = (b.minY + b.maxY) / 2 - ey, cz = (b.minZ + b.maxZ) / 2 - ez;
            double length = Math.sqrt(cx * cx + cy * cy + cz * cz);
            boolean lookedAt = length < 1e-3 || (look.x * cx + look.y * cy + look.z * cz) / length > 0.8;
            double score = (distance + 1) * (lookedAt ? 0.33 : 1.0) * (activeDisplays.containsKey(pos) ? 0.67 : 1.0);
            candidates.put(pos, score);
        }
        int slots = Math.min(NestworldConfig.DECODE_SCREENS.get(), NestworldConfig.MAX_ACTIVE_STREAMS.get());
        var ranked = new java.util.ArrayList<>(candidates.keySet());
        ranked.sort(java.util.Comparator.comparingDouble(candidates::get));
        java.util.Set<BlockPos> chosen = new java.util.HashSet<>(ranked.subList(0, Math.min(slots, ranked.size())));
        long now = System.currentTimeMillis();
        // A decoder opened moments ago keeps its place for 3 s.
        for (BlockPos pos : activeDisplays.keySet())
            if (!chosen.contains(pos) && now - openedAtMs.getOrDefault(pos, 0L) < 3000 && candidates.containsKey(pos)) chosen.add(pos);
        chosenScreens.retainAll(chosen);
        chosenScreens.addAll(chosen);
        for (BlockPos pos : new java.util.ArrayList<>(activeDisplays.keySet()))
            if (!chosen.contains(pos) && candidates.containsKey(pos)) freeze(pos);
        // Last pictures of screens that stopped, changed video or are gone are no longer needed.
        stills.entrySet().removeIf(e -> {
            Display live = activeDisplays.get(e.getKey());
            boolean keep = mc.level.getBlockEntity(e.getKey()) instanceof VideoScreenBlockEntity s && s.isMaster() && !s.isStopped()
                    && s.getCurrentVideoUrl().equals(e.getValue().getUrl()) && candidates.containsKey(e.getKey())
                    && (live == null || live.getTextureId() <= 0);   // gone once the decoder shows a picture again
            if (!keep) e.getValue().release();
            return !keep;
        });
    }

    /** Closes the decoder of a screen that gives way to others, keeping its last picture on the screen. */
    private void freeze(BlockPos pos) {
        Display display = activeDisplays.get(pos);
        if (display == null) return;
        int texture = display.detachPicture();
        var size = display.getDimensions();
        float[] uv = display.uvScale();
        boolean mipmapped = display.hasMipmaps();
        String url = display.getUrl();
        com.mojang.logging.LogUtils.getLogger().info("Nestworld Video: the screen at {} gives way to closer or looked-at ones and keeps its last picture{}", pos, texture > 0 ? "" : " (none yet)");
        stopPlaying(pos);
        var old = texture > 0 && size != null ? stills.put(pos, new com.nestworld.video.client.util.StillDisplay(url, texture, size, uv, mipmapped)) : stills.remove(pos);
        if (old != null) old.release();
        if (texture > 0 && size == null) com.mojang.blaze3d.platform.TextureUtil.releaseTextureId(texture);
        desiredScreens.put(pos, url);   // it comes back when it is among the chosen again
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        double px = mc.player.getX();
        double py = mc.player.getEyeY();
        double pz = mc.player.getZ();

        choosePlayingScreens(mc);
        // Retry screens that were outside the distance/stream budget. This runs on
        // the client tick instead of the block-entity render callback, so opening a
        // WaterMedia decoder cannot stall the render thread.
        for (Map.Entry<BlockPos, String> wanted : desiredScreens.entrySet()) {
            BlockPos pos = wanted.getKey();
            if (!mc.level.hasChunkAt(pos)) continue;
            if (!(mc.level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)
                    || !screen.isMaster() || screen.isStopped()
                    || !wanted.getValue().equals(screen.getCurrentVideoUrl())) {
                desiredScreens.remove(pos, wanted.getValue());
                continue;
            }
            reconcile(screen);
        }

        // Players of a rebuilt screen that no new screen took over within 2 s really stop.
        long nowMs = System.currentTimeMillis();
        orphans.entrySet().removeIf(e -> {
            if (nowMs - e.getValue().sinceMs() < 2000) return false;
            e.getValue().display().release();
            return true;
        });

        boolean watching = false;
        NextUp announce = null;
        for (Map.Entry<BlockPos, Display> entry : activeDisplays.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!mc.level.hasChunkAt(pos) || !(mc.level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen)) {
                reportViewer(pos, false);
                stopPlaying(pos); continue;
            }
            int maxDistance = NestworldConfig.MAX_STREAM_DISTANCE.get();
            if (mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5)
                    > (double) maxDistance * maxDistance) {
                reportViewer(pos, false);
                stopPlaying(pos);
                continue;
            }
            reconcile(screen);
            if (!activeDisplays.containsKey(pos)) continue;
            Display current = activeDisplays.get(pos);
            // The relay is an optimisation: if it cannot be reached (or is too slow to start), fall back to the video host.
            if (current != null && !current.getSourceUrl().equals(current.getUrl())) {
                Long startedAt = relayStartedNanos.get(pos);
                boolean slow = startedAt != null && current.isLoading() && System.nanoTime() - startedAt > 25_000_000_000L;
                if (current.isBroken() || slow) {
                    com.mojang.logging.LogUtils.getLogger().warn("Nestworld Video: the server relay did not work for the screen at {}; using the video host directly", pos);
                    relayFailedUntil.put(pos, System.currentTimeMillis() + 300_000L);
                    stopPlaying(pos);
                    continue;
                }
            }
            if (current != null) current.tick();
            int lower = current == null ? 0 : current.lowerQualitySuggestion();
            if (lower > 0) { lowerQuality(pos, current, lower); continue; }
            // The server's timeline ran past the end of the stream (e.g. the decoder only just started or lagged): report it
            // anyway, otherwise nobody tells the server and auto-next never happens.
            if (current != null && current.getDuration() > 0 && screen.getPlaybackPositionMs() < current.getDuration() - 1000)
                lastEndReports.remove(pos);
            if (current != null && screen.isPlaying() && current.getDuration() > 0
                    && screen.getPlaybackPositionMs() > current.getDuration() + 3000 && current.getPlaybackTime() >= 0
                    && lastEndReports.putIfAbsent(pos, current.getUrl()) == null) {
                recordHistory(pos, current, true);
                Networking.INSTANCE.sendToServer(new PacketVideoEnded(pos, current.getUrl(), Math.max(0, current.getDuration())));
            }
            if (current != null && screen.isPlaying() && current.pollEnded()) {
                recordHistory(pos, current, true);
                Networking.INSTANCE.sendToServer(new PacketVideoEnded(pos, current.getUrl(), Math.max(0, current.getDuration())));
            }
            if (current != null)
                screen.setClientPlaybackAdvancing(current.isPlaying() && !current.isBuffering());
            if (current != null && current.needsInitialSync())
                current.syncPosition(screen.getTimelinePositionMs() >= 0 ? screen.getTimelinePositionMs() : screen.getPlaybackPositionMs());
            long now = mc.level.getGameTime();
            if (current != null && screen.isPlaying() && !current.isBuffering()) {
                Long lastRecord = lastHistoryRecords.get(pos);
                if (lastRecord == null || now - lastRecord >= 100) { lastHistoryRecords.put(pos, now); recordHistory(pos, current, false); }
                long duration = current.getDuration(), time = current.getPlaybackTime();
                if (announce == null && duration > 0 && time >= 0 && duration - time <= NEXT_UP_MS && duration - time > 0
                        && screen.isAutoNext() && !screen.isRepeat() && !screen.isNextCancelled()
                        && mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) < 64 * 64) {
                    var next = com.nestworld.video.NextEpisode.find(categories(), current.getUrl());
                    if (next != null) announce = new NextUp(pos, (int) Math.ceil((duration - time) / 1000.0), next.getTitle());
                }
            }
            if (current != null) correctDrift(pos, screen, current, mc.level.getGameTime());
            Long lastReport = lastViewerReports.get(pos);
            if (lastReport == null || now - lastReport >= 100) {
                reportViewer(pos, true);
                lastViewerReports.put(pos, now);
            }
            // Distance to the screen surface, so the bottom of a 100-block-tall wall is audible.
            var bounds = screen.getRenderBoundingBox();
            double dx = Math.max(bounds.minX - px, Math.max(0, px - bounds.maxX));
            double dy = Math.max(bounds.minY - py, Math.max(0, py - bounds.maxY));
            double dz = Math.max(bounds.minZ - pz, Math.max(0, pz - bounds.maxZ));
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            current = activeDisplays.get(pos);
            // Is this screen playing (so within the stream distance, the loop above skips the rest) and in front of the player?
            // Then tearing would be visible: the same distance at which the screen works applies to VSync.
            if (current != null && current.isPlaying()) {
                var look = mc.player.getViewVector(1.0F);
                double cx = (bounds.minX + bounds.maxX) / 2 - px, cy = (bounds.minY + bounds.maxY) / 2 - py, cz = (bounds.minZ + bounds.maxZ) / 2 - pz;
                double length = Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (length < 1e-3 || (look.x * cx + look.y * cy + look.z * cz) / length > 0.3) watching = true;
            }
            // Room acoustics only matter while the screen is audible, which is also when it is close enough to compute them.
            if (current != null) {
                if (current.isPlaying() && dist < 48) acoustics.update(mc.level, pos, screen, current, mc.level.getGameTime());
                else { current.setAcoustics(null); acoustics.forget(pos); }
                // Lit while a picture is on the screen, also when paused: the last frame keeps lighting the room.
                // A tick without colours (or a step past the distance) must not switch the light off: it times out by itself.
                if (current.glowGrid() != null && dist < 40) glow.update(mc.level, pos, screen, current, mc.level.getGameTime());
            }
            if (current != null) {
                double diagonal = Math.hypot(screen.getScreenCols(), screen.getScreenRows());
                current.applySpatialVolume(com.nestworld.video.client.util.SoundFalloff.factor(dist, diagonal,
                        NestworldConfig.MAX_STREAM_DISTANCE.get()));
                // Speakers sit at the screen's left/right edges; stay within a few blocks of the centre for wide walls.
                var right = screen.getBlockState().getValue(com.nestworld.video.block.VideoScreenBlock.FACING).getCounterClockWise();
                double half = Math.min(screen.getScreenCols() / 2.0, 6.0) * 0.85;
                // Standing close, speakers at the very edges would hard-pan the sound: keep the angle gentle.
                double toCentre = Math.sqrt(Math.pow((bounds.minX + bounds.maxX) / 2 - px, 2) + Math.pow((bounds.minY + bounds.maxY) / 2 - py, 2)
                        + Math.pow((bounds.minZ + bounds.maxZ) / 2 - pz, 2));
                double softness = NestworldConfig.SPATIAL_SOFTNESS.get() / 100.0;
                half = Math.min(half, Math.max(1.0, toCentre * (0.75 - 0.5 * softness)));
                current.setSoundPlacement((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2,
                        (bounds.minZ + bounds.maxZ) / 2, right.getStepX() * half, 0, right.getStepZ() * half);
            }
        }
        nextUp = announce;
        AutoVsync.update(watching);
    }

    /**
     * Every viewer opens its own stream and a slow start leaves it behind the others for good. Compare the decoder with
     * the screen's shared timeline (game clock) and pull it back with a seek when it is clearly off.
     */
    /** Within this the viewers count as together; beyond {@link #SEEK_DRIFT_MS} the decoder jumps, in between it plays a little faster or slower. */
    private static final long IN_STEP_MS = 30, SEEK_DRIFT_MS = 2000;
    /** Speed change per millisecond off (300 ms off = 3 % faster or slower), at most {@link #MAX_RATE_CHANGE}. */
    private static final double RATE_PER_MS = 0.0001, MAX_RATE_CHANGE = 0.03;

    private void correctDrift(BlockPos pos, VideoScreenBlockEntity screen, Display display, long gameTime) {
        long now = com.nestworld.video.SyncClock.nowMs();
        long[] state = driftState.computeIfAbsent(pos, k -> new long[]{0, Long.MIN_VALUE / 2, 0});   // {off-since ms, last seek ms, last log ms}
        long expected = screen.getTimelinePositionMs(), actual = display.getPlaybackTime();
        if (expected < 0 || actual < 0 || !screen.isPlaying() || !display.isPlaying() || display.isBuffering() || display.getDuration() <= 0) {
            state[0] = 0; display.setPlaybackRate(1.0); return;
        }
        long drift = actual - expected;
        if (NestworldConfig.DEBUG_STATS.get() && now - state[2] >= 2000) {
            state[2] = now;
            com.mojang.logging.LogUtils.getLogger().info("Nestworld Video sync: this player is {}ms {} the shared timeline ({}ms vs {}ms)",
                    Math.abs(drift), drift < 0 ? "BEHIND" : "ahead of", actual, expected);
        }
        if (Math.abs(drift) < SEEK_DRIFT_MS) {
            // Even it out softly: a few percent faster or slower is not heard, a jump is seen.
            state[0] = 0;
            double change = Math.abs(drift) <= IN_STEP_MS ? 0 : Math.max(-MAX_RATE_CHANGE, Math.min(MAX_RATE_CHANGE, -drift * RATE_PER_MS));
            display.setPlaybackRate(1.0 + change);
            return;
        }
        display.setPlaybackRate(1.0);
        if (state[0] == 0) state[0] = now;
        // Far off for a second and not jumped in the last 5 s (a seek takes a moment to land).
        if (now - state[0] >= 1000 && now - state[1] >= 5000) {
            state[1] = now; state[0] = 0;
            com.mojang.logging.LogUtils.getLogger().info("Nestworld Video: re-syncing with the other viewers ({}ms off)", drift);
            display.syncPosition(expected);
        }
    }

    private static java.util.List<com.nestworld.video.Category> categories() {
        var config = ClientData.getVideoConfig();
        return config == null || config.getCategories() == null ? java.util.List.of() : config.getCategories();
    }

    /** Remember progress, "watched" and the quality of a catalogue video for the library's resume shelf and marks. */
    private void recordHistory(BlockPos pos, Display display, boolean ended) {
        try {
            String url = display.getUrl();
            for (var category : categories()) {
                if (category == null || category.getVideos() == null) continue;
                for (var video : category.getVideos()) if (video != null && url.equals(video.getUrl())) {
                    var size = display.getDimensions();
                    long time = ended ? display.getDuration() : display.getPlaybackTime();
                    if (time < 0) return;
                    WatchHistory.record(category, video, time, display.getDuration(), size == null ? 0 : size.height, ended);
                    return;
                }
            }
        } catch (RuntimeException ignored) { }
    }

    public void shutdown() {
        AutoVsync.release();
        if (Minecraft.getInstance().getConnection() != null)
            for (BlockPos pos : lastViewerReports.keySet())
                Networking.INSTANCE.sendToServer(new PacketScreenWatch(pos, false));
        WatchHistory.saveNow();
        activeDisplays.values().forEach(Display::release);
        activeDisplays.clear();
        orphans.values().forEach(o -> o.display().release());
        orphans.clear();
        stills.values().forEach(Display::release);
        stills.clear();
        chosenScreens.clear();
        qualityCaps.clear();
        desiredScreens.clear();
        lastViewerReports.clear();
        lastHistoryRecords.clear();
        nextUp = null;
    }
}
