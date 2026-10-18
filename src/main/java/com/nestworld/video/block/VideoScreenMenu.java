package com.nestworld.video.block;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;

public class VideoScreenMenu extends AbstractContainerMenu {

    private final VideoScreenBlockEntity blockEntity;
    private final BlockPos pos;
    private final boolean remoteControl;

    // Конструктор для сервера
    public VideoScreenMenu(int containerId, Inventory playerInventory, VideoScreenBlockEntity blockEntity) {
        this(containerId, playerInventory, blockEntity, false);
    }

    public VideoScreenMenu(int containerId, Inventory playerInventory, VideoScreenBlockEntity blockEntity, boolean remoteControl) {
        super(ModBlocks.VIDEO_SCREEN_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.pos = blockEntity.getBlockPos();
        this.remoteControl = remoteControl;
    }

    // Конструктор для клієнта (отримує дані з мережі)
    public VideoScreenMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        super(ModBlocks.VIDEO_SCREEN_MENU.get(), containerId);
        this.pos = extraData.readBlockPos();
        this.remoteControl = extraData.readBoolean();
        this.blockEntity = playerInventory.player.level().getBlockEntity(this.pos) instanceof VideoScreenBlockEntity screen ? screen : null;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null || blockEntity.isRemoved() || !player.level().hasChunkAt(pos)) return false;
        if (remoteControl) return ScreenRemoteItem.playerHasRemoteFor(player, pos);
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
    }

    public boolean isRemoteControl() { return remoteControl; }

    public VideoScreenBlockEntity getBlockEntity() {
        return blockEntity;
    }
}
