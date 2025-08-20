package com.nestworld.video.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.nestworld.video.Category;
import com.nestworld.video.CinemaMod;
import com.nestworld.video.Video;
import com.nestworld.video.block.VideoScreenBlockEntity; // <--- ОСЬ ЦЕЙ РЯДОК Я ЗАБУВ
import com.nestworld.video.block.VideoScreenMenu;
import com.nestworld.video.client.ClientData;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketControlVideo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.slf4j.Logger;

public class VideoScreenScreen extends AbstractContainerScreen<VideoScreenMenu> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(CinemaMod.MOD_ID, "textures/gui/video_screen.png");
    private Category selectedCategory = null;
    private int scrollOffset = 0;

    public VideoScreenScreen(VideoScreenMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 256;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();

        if (this.menu.getBlockEntity() == null) {
            LOGGER.error("VideoScreenScreen opened without a valid BlockEntity! This may be a timing issue. Closing screen.");
            this.onClose();
            return;
        }
        
        // ... (решта коду залишається без змін)
        
        this.addRenderableWidget(Button.builder(Component.literal("Play"), button -> {
            Networking.INSTANCE.sendToServer(new PacketControlVideo(menu.getBlockEntity().getBlockPos(), "play"));
        }).bounds(leftPos + 10, topPos + 140, 50, 20).build());
        
        this.addRenderableWidget(Button.builder(Component.literal("Pause"), button -> {
            Networking.INSTANCE.sendToServer(new PacketControlVideo(menu.getBlockEntity().getBlockPos(), "pause"));
        }).bounds(leftPos + 65, topPos + 140, 50, 20).build());
        
        this.addRenderableWidget(Button.builder(Component.literal("Stop"), button -> {
            Networking.INSTANCE.sendToServer(new PacketControlVideo(menu.getBlockEntity().getBlockPos(), "stop"));
        }).bounds(leftPos + 120, topPos + 140, 50, 20).build());
        
        if (ClientData.getVideoConfig() != null && !ClientData.getVideoConfig().getCategories().isEmpty()) {
            int buttonY = topPos + 30;
            int buttonIndex = 0;
            
            for (Category category : ClientData.getVideoConfig().getCategories()) {
                if (buttonIndex >= 4) break;
                
                this.addRenderableWidget(Button.builder(Component.literal(category.getName()), button -> {
                    selectedCategory = category;
                    clearWidgets();
                    init();
                }).bounds(leftPos + 180, buttonY, 70, 20).build());
                
                buttonY += 25;
                buttonIndex++;
            }
        }
        
        if (selectedCategory != null && selectedCategory.getVideos() != null) {
            int buttonY = topPos + 30;
            int videoIndex = 0;
            
            for (Video video : selectedCategory.getVideos()) {
                if (videoIndex >= 4) break;
                
                String title = video.getTitle();
                if (title.length() > 15) {
                    title = title.substring(0, 12) + "...";
                }
                
                this.addRenderableWidget(Button.builder(Component.literal(title), button -> {
                    Networking.INSTANCE.sendToServer(new PacketControlVideo(
                        menu.getBlockEntity().getBlockPos(), "select", video.getUrl(), video.getTitle()));
                }).bounds(leftPos + 10, buttonY, 160, 20).build());
                
                buttonY += 25;
                videoIndex++;
            }
            
            this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
                selectedCategory = null;
                clearWidgets();
                init();
            }).bounds(leftPos + 180, topPos + 140, 70, 20).build());
        }
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderTexture(0, TEXTURE);
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        guiGraphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
        
        VideoScreenBlockEntity be = menu.getBlockEntity();
        if (be != null) {
            String currentVideo = be.getCurrentVideoTitle();
            guiGraphics.drawString(this.font, "Current: " + currentVideo, leftPos + 10, topPos + 15, 0x404040, false);
            
            String status = be.isPlaying() ? "Playing" : "Stopped";
            guiGraphics.drawString(this.font, "Status: " + status, leftPos + 10, topPos + 130, 0x404040, false);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }
}