package com.nestworld.video.block;

import com.nestworld.video.Video;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketUpdateVideoScreen;
import net.minecraft.core.BlockPos;
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

    public VideoScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.VIDEO_SCREEN_BLOCK_ENTITY.get(), pos, state);
    }

    // --- NBT & Initial Sync ---
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

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return super.getUpdateTag();
    }

    // --- Menu Provider ---
    @Override
    public Component getDisplayName() {
        return Component.literal("Video Screen");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new VideoScreenMenu(containerId, playerInventory, this);
    }

    // --- Server-Side Control Methods ---
    public void setVideo(Video video) {
        if (video != null) {
            this.currentVideoUrl = video.getUrl();
            this.currentVideoTitle = video.getTitle();
            this.isPlaying = false; // Зупиняємо відтворення при зміні відео
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
        // Можливо, очистити URL, якщо потрібно
        // this.currentVideoUrl = "";
        // this.currentVideoTitle = "No Video Selected";
        syncToClient();
    }

    private void syncToClient() {
        if (level != null && !level.isClientSide) {
            setChanged();
            PacketUpdateVideoScreen packet = new PacketUpdateVideoScreen(worldPosition, currentVideoUrl, isPlaying);
            level.players().forEach(player -> {
                if (player instanceof ServerPlayer) {
                    Networking.INSTANCE.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), packet);
                }
            });
        }
    }

    // --- Getters & Client-Side Setters ---
    public String getCurrentVideoUrl() {
        return currentVideoUrl;
    }
    
    public String getCurrentVideoTitle() {
        return currentVideoTitle;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    // Ці методи викликаються на клієнті через пакет
    public void setVideoUrl(String url) {
        this.currentVideoUrl = url;
    }

    public void setPlaying(boolean playing) {
        this.isPlaying = playing;
    }
}