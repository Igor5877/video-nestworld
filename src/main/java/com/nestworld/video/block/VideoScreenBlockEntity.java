package com.nestworld.video.block;

import com.nestworld.video.SyncClock;

import com.nestworld.video.Category;
import com.nestworld.video.CinemaMod;
import com.nestworld.video.NextEpisode;
import com.nestworld.video.Video;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketUpdateVideoScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.PacketDistributor;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

public class VideoScreenBlockEntity extends BlockEntity implements MenuProvider {
    private String currentVideoUrl = "", currentVideoTitle = "";
    private boolean isPlaying, stopped = true;
    /** Per-screen playback options: continue with the next episode / replay the current one when it ends. */
    private boolean autoNext = true, repeat;
    /** Someone cancelled the announced auto-advance for the current video; cleared when the video changes. */
    private boolean nextCancelled;
    /** Server: viewers should read this screen's video through the server's relay. Clients only see {@link #relayUrl}. */
    private boolean relayActive;
    private String relayUrl = "";
    /** The video on this screen is marked 18+ (decided by the server; viewers without access get a placeholder). */
    private boolean adult;
    /** Server: whether the tiles were last set to give light (null = not decided yet since loading). */
    private Boolean lightApplied;
    /** A big screen lights only an even grid of its tiles: every lit block costs the lighting engine work. */
    private static final int MAX_LIGHT_SOURCES = 150;
    /** Who started the current session and whether other players may take over its controls (kept on the controller). */
    private java.util.UUID sessionOwner;
    private String sessionOwnerName = "";
    private boolean sessionProtection = true;
    /** Media position in milliseconds at the last server tick/sync. */
    private long playbackPositionMs;
    /** Server: {@link SyncClock#nowMs()} at which the playing video was at 0 ms (real time, not game ticks). */
    private long playbackStartedMs = Long.MIN_VALUE;
    /** Client: the shared timeline was at {@code timelinePositionMs} when the server's clock read {@code timelineServerMs}. */
    private long timelinePositionMs, timelineServerMs = Long.MIN_VALUE;
    /** Client monotonic clock anchor; unlike game time it advances while video frames render. */
    private long playbackStartedNanos = Long.MIN_VALUE;
    /** The client clock follows the decoder and pauses during real VLC buffering. */
    private boolean clientPlaybackAdvancing = true;
    private int screenCols = 1, screenRows = 1, tileCol, tileRow;
    private long masterPosLong = Long.MIN_VALUE;

    public VideoScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.VIDEO_SCREEN_BLOCK_ENTITY.get(), pos, state);
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("video_url", currentVideoUrl);
        tag.putString("video_title", currentVideoTitle);
        tag.putBoolean("is_playing", isPlaying);
        tag.putBoolean("stopped", stopped);
        tag.putString("relay_url", currentRelayUrl());
        tag.putBoolean("adult", currentAdult());
        tag.putBoolean("auto_next", autoNext); tag.putBoolean("repeat", repeat); tag.putBoolean("next_cancelled", nextCancelled);
        if (sessionOwner != null) { tag.putUUID("session_owner", sessionOwner); tag.putString("session_owner_name", sessionOwnerName); }
        tag.putBoolean("session_protection", sessionProtection);
        refreshPlaybackPosition();
        tag.putLong("playback_position_ms", playbackPositionMs);
        tag.putInt("screen_cols", screenCols); tag.putInt("screen_rows", screenRows);
        tag.putInt("tile_col", tileCol); tag.putInt("tile_row", tileRow);
        tag.putLong("master_pos", masterPosLong);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        currentVideoUrl = tag.getString("video_url");
        currentVideoTitle = tag.getString("video_title");
        isPlaying = tag.getBoolean("is_playing");
        stopped = tag.contains("stopped") ? tag.getBoolean("stopped") : !isPlaying;
        autoNext = !tag.contains("auto_next") || tag.getBoolean("auto_next");
        repeat = tag.getBoolean("repeat"); nextCancelled = tag.getBoolean("next_cancelled");
        relayUrl = tag.getString("relay_url");
        adult = tag.getBoolean("adult");
        sessionOwner = tag.hasUUID("session_owner") ? tag.getUUID("session_owner") : null;
        sessionOwnerName = tag.getString("session_owner_name");
        sessionProtection = !tag.contains("session_protection") || tag.getBoolean("session_protection");
        playbackPositionMs = Math.max(0, tag.getLong("playback_position_ms"));
        playbackStartedMs = Long.MIN_VALUE;
        timelineServerMs = Long.MIN_VALUE;
        if (tag.contains("server_ms") && isPlaying) {   // a chunk sent to a client: where the timeline was, and when
            SyncClock.oneWay(tag.getLong("server_ms"));
            timelinePositionMs = playbackPositionMs; timelineServerMs = tag.getLong("server_ms");
        }
        playbackStartedNanos = Long.MIN_VALUE;
        clientPlaybackAdvancing = isPlaying;
        screenCols = Math.max(1, Math.min(ScreenLayout.MAX_SIZE, tag.getInt("screen_cols")));
        screenRows = Math.max(1, Math.min(ScreenLayout.MAX_SIZE, tag.getInt("screen_rows")));
        tileCol = Math.max(0, Math.min(screenCols - 1, tag.getInt("tile_col")));
        tileRow = Math.max(0, Math.min(screenRows - 1, tag.getInt("tile_row")));
        masterPosLong = tag.contains("master_pos") ? tag.getLong("master_pos") : Long.MIN_VALUE;
        if (level != null && level.isClientSide && isPlaying && clientPlaybackAdvancing)
            playbackStartedNanos = System.nanoTime() - playbackPositionMs * 1_000_000L;
    }

    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag() { var tag = new CompoundTag(); saveAdditional(tag); tag.putLong("server_ms", SyncClock.nowMs()); return tag; }
    @Override public Component getDisplayName() { return Component.translatable("gui.nestworldvideo.video_screen"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new VideoScreenMenu(id, inventory, this); }
    public boolean isMaster() { return masterPosLong == Long.MIN_VALUE; }
    @Nullable public BlockPos getMasterPos() { return isMaster() ? null : BlockPos.of(masterPosLong); }
    public int getScreenCols() { return screenCols; }
    public int getScreenRows() { return screenRows; }
    public int getTileCol() { return tileCol; }
    public int getTileRow() { return tileRow; }

    @Nullable public VideoScreenBlockEntity controller() {
        if (isMaster()) return this;
        BlockPos pos = getMasterPos();
        if (level == null || !level.hasChunkAt(pos)) return null;
        if (level.getBlockEntity(pos) instanceof VideoScreenBlockEntity master && master.isMaster()
                && master.getBlockState().getValue(VideoScreenBlock.FACING) == getBlockState().getValue(VideoScreenBlock.FACING)
                && tileCol < master.screenCols && tileRow < master.screenRows
                && pos.relative(getBlockState().getValue(VideoScreenBlock.FACING).getCounterClockWise(), tileCol)
                      .above(tileRow).equals(worldPosition)) return master;
        return null;
    }

    /** Validate the entire connected rectangle before modifying any existing screen. */
    public ScreenLayout assemble() {
        ScreenLayout layout = ScreenLayout.detect(level, worldPosition);
        if (!layout.valid() || level.isClientSide) return layout;
        VideoScreenBlockEntity previous = controller();
        String url = previous == null ? "" : previous.currentVideoUrl;
        String title = previous == null ? "" : previous.currentVideoTitle;
        Set<VideoScreenBlockEntity> masters = new HashSet<>();
        for (BlockPos pos : layout.tiles()) {
            var tile = (VideoScreenBlockEntity) level.getBlockEntity(pos);
            var master = tile.controller();
            if (master != null) masters.add(master);
        }
        if (masters.size() == 1 && previous != null && previous.worldPosition.equals(layout.origin())
                && previous.screenCols == layout.cols() && previous.screenRows == layout.rows()) return layout;
        if (url.isBlank()) {
            var inherited = masters.stream().filter(m -> !m.currentVideoUrl.isBlank())
                    .min(java.util.Comparator.comparingLong(m -> m.worldPosition.asLong())).orElse(null);
            if (inherited != null) { url = inherited.currentVideoUrl; title = inherited.currentVideoTitle; }
        }
        for (var master : masters)
            if (master.screenCols > 1 || master.screenRows > 1 || !master.currentVideoUrl.isEmpty()) master.resetLayout();
        Direction right = getBlockState().getValue(VideoScreenBlock.FACING).getCounterClockWise();
        for (BlockPos pos : layout.tiles()) {
            var tile = (VideoScreenBlockEntity) level.getBlockEntity(pos);
            tile.screenCols = layout.cols(); tile.screenRows = layout.rows();
            tile.tileCol = (pos.getX() - layout.origin().getX()) * right.getStepX()
                    + (pos.getZ() - layout.origin().getZ()) * right.getStepZ();
            tile.tileRow = pos.getY() - layout.origin().getY();
            tile.masterPosLong = pos.equals(layout.origin()) ? Long.MIN_VALUE : layout.origin().asLong();
            tile.currentVideoUrl = ""; tile.currentVideoTitle = "";
            tile.isPlaying = false; tile.stopped = true;
            tile.updateBlock();
        }
        var master = (VideoScreenBlockEntity) level.getBlockEntity(layout.origin());
        master.currentVideoUrl = url; master.currentVideoTitle = title;
        master.syncToClient();
        return layout;
    }

    /** Called from any tile, including before it is broken. Never force-load chunks. */
    public void resetLayout() {
        if (level == null || level.isClientSide) return;
        VideoScreenBlockEntity master = controller();
        if (master != null && master != this) { master.resetLayout(); return; }
        if (!currentVideoUrl.isEmpty() || isPlaying) stop();
        long owner = worldPosition.asLong();
        Direction facing = getBlockState().getValue(VideoScreenBlock.FACING);
        // Also recognize layouts saved by old versions that used the opposite width direction.
        for (Direction right : new Direction[]{facing.getCounterClockWise(), facing.getClockWise()}) {
            for (int row = 0; row < screenRows; row++) {
                for (int col = 0; col < screenCols; col++) {
                    BlockPos pos = worldPosition.relative(right, col).above(row);
                    if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof VideoScreenBlockEntity tile
                            && tile.masterPosLong == owner) tile.detach();
                }
            }
        }
        detach();
    }

    private void detach() {
        screenCols = screenRows = 1; tileCol = tileRow = 0; masterPosLong = Long.MIN_VALUE;
        isPlaying = false; stopped = true;
        updateBlock();
    }
    private void updateBlock() {
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }
    public void setVideo(Video video) {
        currentVideoUrl = video.getUrl(); currentVideoTitle = video.getTitle(); nextCancelled = false;
        playbackPositionMs = 0; playbackStartedMs = Long.MIN_VALUE;
        isPlaying = false; stopped = true; syncToClient();
    }
    public void play() {
        if (!currentVideoUrl.isBlank()) {
            refreshPlaybackPosition();
            playbackStartedMs = SyncClock.nowMs() - playbackPositionMs;
            isPlaying = true; stopped = false; syncToClient();
        }
    }
    /**
     * A client reports that {@code endedUrl} finished. Depending on the screen's options: replay it, continue with the
     * next playable episode of the same release and dub, or stop (last episode, auto-next off or cancelled). Stale or
     * duplicate reports (several clients, or the screen already moved on) are ignored.
     */
    public void playNext(String endedUrl) {
        if (!isMaster() || !isPlaying || !currentVideoUrl.equals(endedUrl)) return;
        Video next = null;
        if (repeat) {
            playbackPositionMs = 0; playbackStartedMs = SyncClock.nowMs();
            syncToClient();
            return;
        }
        if (autoNext && !nextCancelled && CinemaMod.videoConfig != null)
            next = NextEpisode.find(CinemaMod.videoConfig.getCategories(), endedUrl);
        if (next == null) { stop(); return; }
        currentVideoUrl = next.getUrl(); currentVideoTitle = next.getTitle(); nextCancelled = false;
        playbackPositionMs = 0; playbackStartedMs = SyncClock.nowMs();
        isPlaying = true; stopped = false; syncToClient();
    }
    public void setAutoNext(boolean value) { autoNext = value; if (value) nextCancelled = false; syncToClient(); }
    public void setRepeat(boolean value) { repeat = value; syncToClient(); }
    /** Cancel the announced auto-advance of the video that is playing now. */
    public void cancelNext() { if (!nextCancelled) { nextCancelled = true; syncToClient(); } }
    /** Server side: the address viewers should open instead of the video host, or "" for direct streaming. */
    private String currentRelayUrl() {
        return level == null || level.isClientSide || !relayActive ? "" : com.nestworld.video.relay.RelayManager.clientUrl(currentVideoUrl);
    }
    public void setRelay(boolean active) { if (relayActive != active) { relayActive = active; syncToClient(); } }
    /** Client side: where to read the video from while the server relays it ("" = the video's own URL; may hold {host}). */
    public String getRelayUrl() { return relayUrl; }
    private boolean currentAdult() {
        return level != null && !level.isClientSide ? com.nestworld.video.content.AdultAccess.isAdultUrl(currentVideoUrl) : adult;
    }
    public boolean isAdult() { return adult; }
    @Nullable public java.util.UUID getSessionOwner() { return sessionOwner; }
    public String getSessionOwnerName() { return sessionOwnerName; }
    public boolean isSessionProtection() { return sessionProtection; }
    public void setSessionOwner(@Nullable ServerPlayer player) {
        java.util.UUID id = player == null ? null : player.getUUID();
        if (java.util.Objects.equals(id, sessionOwner)) return;
        sessionOwner = id; sessionOwnerName = player == null ? "" : player.getGameProfile().getName();
        syncToClient();
    }
    public void setSessionProtection(boolean value) { if (sessionProtection != value) { sessionProtection = value; syncToClient(); } }
    public boolean isAutoNext() { return autoNext; }
    public boolean isRepeat() { return repeat; }
    public boolean isNextCancelled() { return nextCancelled; }
    public void pause() {
        refreshPlaybackPosition();
        isPlaying = false; playbackStartedMs = Long.MIN_VALUE; syncToClient();
    }
    /** Pause at the requesting client's actual VLC media time. */
    public void pauseAt(long positionMs) {
        playbackPositionMs = Math.max(0, positionMs);
        isPlaying = false;
        playbackStartedMs = Long.MIN_VALUE;
        syncToClient();
    }
    /**
     * The user pressed Stop: the screen stops but remembers where it was, so Play continues from there.
     * ({@link #stop()} resets to the start: used when a video has finished or the layout is torn down.)
     */
    public void stopKeepingPosition(long clientPositionMs) {
        refreshPlaybackPosition();
        if (clientPositionMs >= 0) playbackPositionMs = clientPositionMs;
        isPlaying = false; stopped = true;
        playbackStartedMs = Long.MIN_VALUE; playbackStartedNanos = Long.MIN_VALUE;
        syncToClient();
    }
    public void stop() {
        isPlaying = false; stopped = true; playbackPositionMs = 0;
        playbackStartedMs = Long.MIN_VALUE; playbackStartedNanos = Long.MIN_VALUE; syncToClient();
    }
    public void seekBy(long deltaMs, @Nullable ServerPlayer requester) {
        refreshPlaybackPosition();
        playbackPositionMs = Math.max(0, playbackPositionMs + deltaMs);
        if (isPlaying) playbackStartedMs = SyncClock.nowMs() - playbackPositionMs;
        syncToClient(requester);
    }
    private void refreshPlaybackPosition() {
        if (!isPlaying || level == null) return;
        if (level.isClientSide) {
            if (!clientPlaybackAdvancing) return;
            if (playbackStartedNanos == Long.MIN_VALUE)
                playbackStartedNanos = System.nanoTime() - playbackPositionMs * 1_000_000L;
            playbackPositionMs = Math.max(0, (System.nanoTime() - playbackStartedNanos) / 1_000_000L);
            return;
        }
        if (playbackStartedMs == Long.MIN_VALUE) return;
        playbackPositionMs = Math.max(0, SyncClock.nowMs() - playbackStartedMs);
    }
    public long getPlaybackPositionMs() {
        refreshPlaybackPosition();
        return playbackPositionMs;
    }
    /**
     * Client: where the shared timeline of this screen is right now, from the server's last word and the estimated
     * server clock (not from this client's decoder), or -1 when paused/stopped. Every viewer computes the same value,
     * so it is what decoders are held to.
     */
    public long getTimelinePositionMs() {
        if (!isPlaying || timelineServerMs == Long.MIN_VALUE) return -1;
        return Math.max(0, timelinePositionMs + SyncClock.serverNowMs() - timelineServerMs);
    }
    /** Server: the timeline position and the server clock reading it belongs to (for a pulse to a viewer). */
    public long[] timelineNow() {
        refreshPlaybackPosition();
        return new long[]{playbackPositionMs, SyncClock.nowMs()};
    }
    /** Client: the server's periodic word on where the timeline is. */
    public void applyClock(long positionMs, long serverMs) {
        if (!isPlaying) return;
        timelinePositionMs = Math.max(0, positionMs); timelineServerMs = serverMs;
    }
    private void syncToClient() {
        syncToClient(null);
    }
    /** Every {@code step}-th tile in each direction of a screen of {@code tiles} tiles is a light source. */
    public static int lightStep(int tiles) {
        return tiles <= MAX_LIGHT_SOURCES ? 1 : (int) Math.ceil(Math.sqrt(tiles / (double) MAX_LIGHT_SOURCES));
    }

    /** A playing screen lights its surroundings (turned off by content config {@code screen.light_when_playing}). */
    public void updateLight() {
        if (level == null || level.isClientSide || !isMaster()) return;
        boolean want = !stopped && !currentVideoUrl.isBlank() && com.nestworld.video.content.ContentConfig.SCREEN_LIGHT.get();   // playing or paused on a frame
        if (lightApplied != null && lightApplied == want) return;
        lightApplied = want;
        Direction right = getBlockState().getValue(VideoScreenBlock.FACING).getCounterClockWise();
        int count = screenCols * screenRows;
        int step = lightStep(count);
        for (int row = 0; row < screenRows; row++) for (int col = 0; col < screenCols; col++) {
            BlockPos pos = worldPosition.relative(right, col).above(row);
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof VideoScreenBlock)) continue;
            boolean lit = want && (step == 1 || (col % step == step / 2 && row % step == step / 2));
            if (state.getValue(VideoScreenBlock.LIT) != lit) level.setBlock(pos, state.setValue(VideoScreenBlock.LIT, lit), 3);
        }
    }

    public void syncToClient(@Nullable ServerPlayer extraRecipient) {
        if (level == null || level.isClientSide) return;
        updateLight();
        updateBlock();
        var packet = new PacketUpdateVideoScreen(worldPosition, currentVideoUrl, currentVideoTitle, isPlaying, stopped,
                getPlaybackPositionMs(), SyncClock.nowMs(), autoNext, repeat, nextCancelled, currentRelayUrl(), currentAdult());
        boolean sentToExtra = false;
        for (Player player : level.players()) if (player instanceof ServerPlayer serverPlayer
                && serverPlayer.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY() + .5,
                worldPosition.getZ() + .5) <= 256 * 256) {
            Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> serverPlayer), packet);
            if (serverPlayer == extraRecipient) sentToExtra = true;
        }
        if (extraRecipient != null && !sentToExtra)
            Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> extraRecipient), packet);
    }
    public String getCurrentVideoUrl() { return currentVideoUrl; }
    public String getCurrentVideoTitle() { return currentVideoTitle; }
    public boolean isPlaying() { return isPlaying; }
    public boolean isStopped() { return stopped; }
    public void updatePlayback(String url, String title, boolean playing, boolean stopped, long positionMs, long serverMs,
                               boolean autoNext, boolean repeat, boolean nextCancelled, String relayUrl, boolean adult) {
        this.adult = adult;
        this.relayUrl = relayUrl == null ? "" : relayUrl;
        this.autoNext = autoNext; this.repeat = repeat; this.nextCancelled = nextCancelled;
        currentVideoUrl = url; currentVideoTitle = title; isPlaying = playing; this.stopped = stopped;
        playbackPositionMs = Math.max(0, positionMs);
        // The shared timeline keeps advancing on the server's clock, also while this client's decoder buffers.
        timelinePositionMs = playbackPositionMs;
        timelineServerMs = playing ? serverMs : Long.MIN_VALUE;
        if (playing) playbackPositionMs = getTimelinePositionMs();   // the packet took a moment to arrive
        playbackStartedNanos = level != null && level.isClientSide && playing
                ? System.nanoTime() - playbackPositionMs * 1_000_000L
                : Long.MIN_VALUE;
        clientPlaybackAdvancing = playing;
    }

    /** Follow actual decoder playback so network stalls do not advance the displayed media clock. */
    public void setClientPlaybackAdvancing(boolean advancing) {
        if (level == null || !level.isClientSide || !isPlaying || clientPlaybackAdvancing == advancing) return;
        refreshPlaybackPosition();
        clientPlaybackAdvancing = advancing;
        playbackStartedNanos = advancing
                ? System.nanoTime() - playbackPositionMs * 1_000_000L
                : Long.MIN_VALUE;
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level == null) return;
        if (level.isClientSide) return;
        if (isPlaying && playbackStartedMs == Long.MIN_VALUE)
            playbackStartedMs = SyncClock.nowMs() - playbackPositionMs;
        BlockPos owner = isMaster() ? worldPosition : getMasterPos();
        if (!level.hasChunkAt(owner)) return;
        // Deferred until all block entities in this chunk have finished loading.
        if (!level.getBlockTicks().hasScheduledTick(owner, getBlockState().getBlock()))
            level.scheduleTick(owner, getBlockState().getBlock(), 1);
        if (!isMaster() && controller() == null) detach();
    }

    @Override public void setRemoved() {
        super.setRemoved();
    }

    public void validateLoadedTiles() {
        if (level == null || level.isClientSide || !isMaster()) return;
        Direction facing = getBlockState().getValue(VideoScreenBlock.FACING);
        for (int row = 0; row < screenRows; row++) for (int col = 0; col < screenCols; col++) {
            BlockPos pos = worldPosition.relative(facing.getCounterClockWise(), col).above(row);
            if (!level.hasChunkAt(pos)) continue;
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof VideoScreenBlock) || state.getValue(VideoScreenBlock.FACING) != facing) {
                resetLayout(); return;
            }
        }
    }

    @Override public AABB getRenderBoundingBox() {
        Direction right = getBlockState().getValue(VideoScreenBlock.FACING).getCounterClockWise();
        BlockPos end = worldPosition.relative(right, screenCols - 1).above(screenRows - 1);
        return new AABB(worldPosition).minmax(new AABB(end)).inflate(0.01);
    }
}
