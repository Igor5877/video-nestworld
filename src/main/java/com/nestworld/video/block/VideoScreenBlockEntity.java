package com.nestworld.video.block;

import com.nestworld.video.CinemaMod;
import com.nestworld.video.Video;
import com.nestworld.video.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class VideoScreenBlockEntity extends BlockEntity implements MenuProvider {
    
    private String currentVideoUrl = "";
    private String currentVideoTitle = "No Video Selected";
    private boolean isPlaying = false;
    
    public VideoScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.VIDEO_SCREEN_BLOCK_ENTITY.get(), pos, state);
    }
    
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("video_url", currentVideoUrl);
        tag.putString("video_title", currentVideoTitle);
        tag.putBoolean("is_playing", isPlaying);
    }
    
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        currentVideoUrl = tag.getString("video_url");
        currentVideoTitle = tag.getString("video_title");
        isPlaying = tag.getBoolean("is_playing");
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
    
    // Методи для керування відео
    public void setVideo(Video video) {
        if (video != null) {
            this.currentVideoUrl = video.getUrl();
            this.currentVideoTitle = video.getTitle();
            setChanged();
            // Синхронізуємо з клієнтами
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }
    
    public void play() {
        this.isPlaying = true;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    
    public void pause() {
        this.isPlaying = false;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    
    public void stop() {
        this.isPlaying = false;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    
    // Геттери
    public String getCurrentVideoUrl() {
        return currentVideoUrl;
    }
    
    public String getCurrentVideoTitle() {
        return currentVideoTitle;
    }
    
    public boolean isPlaying() {
        return isPlaying;
    }
    
    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
    
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }
}