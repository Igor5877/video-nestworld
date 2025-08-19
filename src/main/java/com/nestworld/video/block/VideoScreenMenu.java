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
    
    // Конструктор для сервера
    public VideoScreenMenu(int containerId, Inventory playerInventory, VideoScreenBlockEntity blockEntity) {
        super(ModBlocks.VIDEO_SCREEN_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.pos = blockEntity.getBlockPos();
    }
    
    // Конструктор для клієнта (отримує дані з мережі)
    public VideoScreenMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        super(ModBlocks.VIDEO_SCREEN_MENU.get(), containerId);
        this.pos = extraData.readBlockPos();
        this.blockEntity = (VideoScreenBlockEntity) playerInventory.player.level().getBlockEntity(this.pos);
    }
    
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
    
    @Override
    public boolean stillValid(Player player) {
        return blockEntity != null && 
               player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
    }
    
    public VideoScreenBlockEntity getBlockEntity() {
        return blockEntity;
    }
}