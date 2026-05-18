package com.nestworld.video.block;

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
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;

public class VideoScreenBlockEntity extends BlockEntity implements MenuProvider {

    private String currentVideoUrl = "";
    private String currentVideoTitle = "No Video Selected";
    private boolean isPlaying = false;

    // Multi-block screen grid fields.
    // masterPosLong == Long.MIN_VALUE means this block IS the master (or standalone 1×1).
    private int screenCols = 1;
    private int screenRows = 1;
    private int tileCol = 0;   // column index, 0 = left from viewer's perspective
    private int tileRow = 0;   // row index, 0 = bottom
    private long masterPosLong = Long.MIN_VALUE;

    public VideoScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.VIDEO_SCREEN_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("video_url", currentVideoUrl);
        tag.putString("video_title", currentVideoTitle);
        tag.putBoolean("is_playing", isPlaying);
        tag.putInt("screen_cols", screenCols);
        tag.putInt("screen_rows", screenRows);
        tag.putInt("tile_col", tileCol);
        tag.putInt("tile_row", tileRow);
        tag.putLong("master_pos", masterPosLong);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        currentVideoUrl = tag.getString("video_url");
        currentVideoTitle = tag.getString("video_title");
        isPlaying = tag.getBoolean("is_playing");
        screenCols = Math.max(1, tag.getInt("screen_cols"));
        screenRows = Math.max(1, tag.getInt("screen_rows"));
        tileCol = tag.getInt("tile_col");
        tileRow = tag.getInt("tile_row");
        masterPosLong = tag.contains("master_pos") ? tag.getLong("master_pos") : Long.MIN_VALUE;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Video Screen");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new VideoScreenMenu(containerId, playerInventory, this);
    }

    // --- Grid accessors ---

    public boolean isMaster() {
        return masterPosLong == Long.MIN_VALUE;
    }

    @Nullable
    public BlockPos getMasterPos() {
        return masterPosLong == Long.MIN_VALUE ? null : BlockPos.of(masterPosLong);
    }

    public int getScreenCols() { return screenCols; }
    public int getScreenRows() { return screenRows; }
    public int getTileCol()    { return tileCol; }
    public int getTileRow()    { return tileRow; }

    // --- Layout management (server-side) ---

    /**
     * Configure this block as the master of a screenCols×screenRows grid.
     * Scans adjacent blocks (right = facing.getClockWise(), up = UP) and marks
     * them as slaves pointing back to this block. The master is always tile (0,0)
     * i.e. bottom-left from the viewer's perspective.
     */
    public void applyLayout(int cols, int rows) {
        if (level == null || level.isClientSide) return;

        Direction facing = getBlockState().getValue(VideoScreenBlock.FACING);
        Direction widthDir = facing.getCounterClockWise(); // viewer's right

        // Clear the previous layout first
        clearSlaves(facing);

        // Configure this block as master at tile (0,0)
        screenCols = cols;
        screenRows = rows;
        tileCol = 0;
        tileRow = 0;
        masterPosLong = Long.MIN_VALUE;
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);

        // Configure each slave in the grid
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (col == 0 && row == 0) continue;
                BlockPos slavePos = worldPosition.relative(widthDir, col).above(row);
                if (level.getBlockEntity(slavePos) instanceof VideoScreenBlockEntity slave) {
                    slave.screenCols = cols;
                    slave.screenRows = rows;
                    slave.tileCol = col;
                    slave.tileRow = row;
                    slave.masterPosLong = worldPosition.asLong();
                    slave.setChanged();
                    level.sendBlockUpdated(slavePos, slave.getBlockState(), slave.getBlockState(), 3);
                }
            }
        }
    }

    /** Reset this master's slaves back to standalone 1×1 blocks. */
    public void resetLayout() {
        if (level == null || level.isClientSide || !isMaster()) return;
        Direction facing = getBlockState().getValue(VideoScreenBlock.FACING);
        clearSlaves(facing);
        screenCols = 1;
        screenRows = 1;
        tileCol = 0;
        tileRow = 0;
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private void clearSlaves(Direction facing) {
        if (level == null) return;
        long myPos = worldPosition.asLong();
        // Check both horizontal directions so blocks configured by older code (which used
        // getClockWise instead of getCounterClockWise) are also found and reset properly.
        for (Direction widthDir : new Direction[]{facing.getClockWise(), facing.getCounterClockWise()}) {
            for (int row = 0; row < screenRows; row++) {
                for (int col = 0; col < screenCols; col++) {
                    if (col == 0 && row == 0) continue;
                    BlockPos slavePos = worldPosition.relative(widthDir, col).above(row);
                    if (level.getBlockEntity(slavePos) instanceof VideoScreenBlockEntity slave
                            && slave.masterPosLong == myPos) {
                        slave.screenCols = 1;
                        slave.screenRows = 1;
                        slave.tileCol = 0;
                        slave.tileRow = 0;
                        slave.masterPosLong = Long.MIN_VALUE;
                        slave.setChanged();
                        level.sendBlockUpdated(slavePos, slave.getBlockState(), slave.getBlockState(), 3);
                    }
                }
            }
        }
    }

    // --- Video control ---

    public void setVideo(Video video) {
        if (video != null) {
            this.currentVideoUrl = video.getUrl();
            this.currentVideoTitle = video.getTitle();
            this.isPlaying = false;
            syncToClient();
        }
    }

    public void play() {
        this.isPlaying = true;
        syncToClient();
    }

    public void pause() {
        this.isPlaying = false;
        syncToClient();
    }

    public void stop() {
        this.isPlaying = false;
        syncToClient();
    }

    private void syncToClient() {
        if (level != null && !level.isClientSide) {
            setChanged();
            PacketUpdateVideoScreen packet = new PacketUpdateVideoScreen(worldPosition, currentVideoUrl, currentVideoTitle, isPlaying);
            level.players().forEach(player -> {
                if (player instanceof ServerPlayer) {
                    Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), packet);
                }
            });
        }
    }

    public String getCurrentVideoUrl()   { return currentVideoUrl; }
    public String getCurrentVideoTitle() { return currentVideoTitle; }
    public boolean isPlaying()           { return isPlaying; }

    public void setVideoUrl(String url)        { this.currentVideoUrl = url; }
    public void setVideoTitle(String title)    { this.currentVideoTitle = title; }
    public void setPlaying(boolean playing)    { this.isPlaying = playing; }
}
