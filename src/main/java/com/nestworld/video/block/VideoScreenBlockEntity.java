package com.nestworld.video.block;

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
    /** Media position in milliseconds at the last server tick/sync. */
    private long playbackPositionMs;
    /** Server game tick at which playbackPositionMs was measured for a playing screen. */
    private long playbackStartedTick = Long.MIN_VALUE;
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
        tag.putBoolean("auto_next", autoNext); tag.putBoolean("repeat", repeat); tag.putBoolean("next_cancelled", nextCancelled);
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
        playbackPositionMs = Math.max(0, tag.getLong("playback_position_ms"));
        playbackStartedTick = Long.MIN_VALUE;
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
    @Override public CompoundTag getUpdateTag() { var tag = new CompoundTag(); saveAdditional(tag); return tag; }
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
        playbackPositionMs = 0; playbackStartedTick = Long.MIN_VALUE;
        isPlaying = false; stopped = true; syncToClient();
    }
    public void play() {
        if (!currentVideoUrl.isBlank()) {
            refreshPlaybackPosition();
            long now = level == null ? 0 : level.getGameTime();
            playbackStartedTick = now - playbackPositionMs / 50;
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
            playbackPositionMs = 0; playbackStartedTick = level == null ? Long.MIN_VALUE : level.getGameTime();
            syncToClient();
            return;
        }
        if (autoNext && !nextCancelled && CinemaMod.videoConfig != null)
            next = NextEpisode.find(CinemaMod.videoConfig.getCategories(), endedUrl);
        if (next == null) { stop(); return; }
        currentVideoUrl = next.getUrl(); currentVideoTitle = next.getTitle(); nextCancelled = false;
        playbackPositionMs = 0; playbackStartedTick = level == null ? Long.MIN_VALUE : level.getGameTime();
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
    public boolean isAutoNext() { return autoNext; }
    public boolean isRepeat() { return repeat; }
    public boolean isNextCancelled() { return nextCancelled; }
    public void pause() {
        refreshPlaybackPosition();
        isPlaying = false; playbackStartedTick = Long.MIN_VALUE; syncToClient();
    }
    /** Pause at the requesting client's actual VLC media time. */
    public void pauseAt(long positionMs) {
        playbackPositionMs = Math.max(0, positionMs);
        isPlaying = false;
        playbackStartedTick = Long.MIN_VALUE;
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
        playbackStartedTick = Long.MIN_VALUE; playbackStartedNanos = Long.MIN_VALUE;
        syncToClient();
    }
    public void stop() {
        isPlaying = false; stopped = true; playbackPositionMs = 0;
        playbackStartedTick = Long.MIN_VALUE; playbackStartedNanos = Long.MIN_VALUE; syncToClient();
    }
    public void seekBy(long deltaMs, @Nullable ServerPlayer requester) {
        refreshPlaybackPosition();
        playbackPositionMs = Math.max(0, playbackPositionMs + deltaMs);
        if (isPlaying && level != null) playbackStartedTick = level.getGameTime() - playbackPositionMs / 50L;
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
        if (playbackStartedTick == Long.MIN_VALUE) return;
        long elapsedTicks = Math.max(0, level.getGameTime() - playbackStartedTick);
        playbackPositionMs = Math.max(0, elapsedTicks * 50L);
    }
    public long getPlaybackPositionMs() {
        refreshPlaybackPosition();
        return playbackPositionMs;
    }
    /**
     * Where the shared timeline of this screen is right now, derived from the game clock only (not from this client's
     * decoder), or -1 when paused/stopped. Every viewer computes the same value, so it is what decoders are held to.
     */
    public long getTimelinePositionMs() {
        if (!isPlaying || level == null || playbackStartedTick == Long.MIN_VALUE) return -1;
        return Math.max(0, (level.getGameTime() - playbackStartedTick) * 50L);
    }
    public long getPlaybackServerGameTime() {
        return level == null ? 0 : level.getGameTime();
    }
    private void syncToClient() {
        syncToClient(null);
    }
    public void syncToClient(@Nullable ServerPlayer extraRecipient) {
        if (level == null || level.isClientSide) return;
        updateBlock();
        var packet = new PacketUpdateVideoScreen(worldPosition, currentVideoUrl, currentVideoTitle, isPlaying, stopped,
                getPlaybackPositionMs(), level.getGameTime(), autoNext, repeat, nextCancelled, currentRelayUrl());
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
    public void updatePlayback(String url, String title, boolean playing, boolean stopped, long positionMs,
                               boolean autoNext, boolean repeat, boolean nextCancelled, String relayUrl) {
        this.relayUrl = relayUrl == null ? "" : relayUrl;
        this.autoNext = autoNext; this.repeat = repeat; this.nextCancelled = nextCancelled;
        currentVideoUrl = url; currentVideoTitle = title; isPlaying = playing; this.stopped = stopped;
        playbackPositionMs = Math.max(0, positionMs);
        // Keep a client-side copy of the shared timeline advancing while its
        // decoder buffers, so the first seek targets the current playback time.
        playbackStartedTick = level != null && level.isClientSide && playing
                ? level.getGameTime() - playbackPositionMs / 50L
                : Long.MIN_VALUE;
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
        if (isPlaying && playbackStartedTick == Long.MIN_VALUE)
            playbackStartedTick = level.getGameTime() - playbackPositionMs / 50;
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
