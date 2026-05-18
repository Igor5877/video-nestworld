package com.nestworld.video.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.nestworld.video.Category;
import com.nestworld.video.CinemaMod;
import com.nestworld.video.Video;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.block.VideoScreenMenu;
import com.nestworld.video.client.ClientData;
import com.nestworld.video.client.ClientVideoManager;
import com.nestworld.video.client.util.Display;
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

    // Layout controls state (cols/rows the user wants to apply).
    // Initialized from the block entity only on the very first open.
    private int layoutCols = 1;
    private int layoutRows = 1;
    private boolean layoutInitialized = false;

    public VideoScreenScreen(VideoScreenMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 256;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();

        VideoScreenBlockEntity be = menu.getBlockEntity();
        if (be == null) {
            LOGGER.error("VideoScreenScreen opened without a valid BlockEntity! Closing screen.");
            this.onClose();
            return;
        }

        // Initialise layout controls from the block entity only on first open.
        // Subsequent calls (triggered by +/- buttons via clearWidgets+init) must
        // preserve the values the user is editing, NOT reset them from the server state.
        if (!layoutInitialized) {
            layoutCols = be.getScreenCols();
            layoutRows = be.getScreenRows();
            layoutInitialized = true;
        }

        // --- Playback controls (left, bottom row) ---
        addRenderableWidget(Button.builder(Component.literal("Play"), button ->
            Networking.INSTANCE.sendToServer(new PacketControlVideo(be.getBlockPos(), "play"))
        ).bounds(leftPos + 10, topPos + 140, 50, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Pause"), button ->
            Networking.INSTANCE.sendToServer(new PacketControlVideo(be.getBlockPos(), "pause"))
        ).bounds(leftPos + 65, topPos + 140, 50, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Stop"), button ->
            Networking.INSTANCE.sendToServer(new PacketControlVideo(be.getBlockPos(), "stop"))
        ).bounds(leftPos + 120, topPos + 140, 50, 20).build());

        // --- Volume controls ---
        addRenderableWidget(Button.builder(Component.literal("Vol -"), button -> {
            Display d = ClientVideoManager.getInstance().getDisplay(be.getBlockPos());
            if (d != null) d.setVolume(d.getVolume() - 10);
        }).bounds(leftPos + 10, topPos + 115, 35, 15).build());

        addRenderableWidget(Button.builder(Component.literal("Vol +"), button -> {
            Display d = ClientVideoManager.getInstance().getDisplay(be.getBlockPos());
            if (d != null) d.setVolume(d.getVolume() + 10);
        }).bounds(leftPos + 50, topPos + 115, 35, 15).build());

        // --- Category list (right column) ---
        if (ClientData.getVideoConfig() != null && !ClientData.getVideoConfig().getCategories().isEmpty()) {
            int buttonY = topPos + 30;
            int buttonIndex = 0;
            for (Category category : ClientData.getVideoConfig().getCategories()) {
                if (buttonIndex >= 4) break;
                addRenderableWidget(Button.builder(Component.literal(category.getName()), button -> {
                    selectedCategory = category;
                    clearWidgets();
                    init();
                }).bounds(leftPos + 180, buttonY + buttonIndex * 25, 70, 20).build());
                buttonIndex++;
            }
        }

        // --- Video list (left/centre column, shown when category selected) ---
        if (selectedCategory != null && selectedCategory.getVideos() != null) {
            int buttonY = topPos + 30;
            int videoIndex = 0;
            for (Video video : selectedCategory.getVideos()) {
                if (videoIndex >= 4) break;
                String label = video.getTitle();
                if (label.length() > 15) label = label.substring(0, 12) + "...";
                addRenderableWidget(Button.builder(Component.literal(label), button ->
                    Networking.INSTANCE.sendToServer(new PacketControlVideo(
                        be.getBlockPos(), "select", video.getUrl(), video.getTitle()))
                ).bounds(leftPos + 10, buttonY + videoIndex * 25, 160, 20).build());
                videoIndex++;
            }

            addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
                selectedCategory = null;
                clearWidgets();
                init();
            }).bounds(leftPos + 180, topPos + 140, 70, 20).build());
        }

        // --- Screen layout controls (right column, master only) ---
        // Only show for the master block; slaves can't change the layout.
        if (be.isMaster() && selectedCategory == null) {
            int rx = leftPos + 180; // right-column X
            int ry = topPos + 125; // start below categories

            // Width row: [W-] [W+]
            addRenderableWidget(Button.builder(Component.literal("W-"), button -> {
                if (layoutCols > 1) { layoutCols--; clearWidgets(); init(); }
            }).bounds(rx, ry, 20, 14).build());

            addRenderableWidget(Button.builder(Component.literal("W+"), button -> {
                if (layoutCols < 16) { layoutCols++; clearWidgets(); init(); }
            }).bounds(rx + 22, ry, 20, 14).build());

            // Height row: [H-] [H+]
            addRenderableWidget(Button.builder(Component.literal("H-"), button -> {
                if (layoutRows > 1) { layoutRows--; clearWidgets(); init(); }
            }).bounds(rx + 46, ry, 20, 14).build());

            addRenderableWidget(Button.builder(Component.literal("H+"), button -> {
                if (layoutRows < 16) { layoutRows++; clearWidgets(); init(); }
            }).bounds(rx + 68, ry, 20, 14).build());

            // Apply layout button
            addRenderableWidget(Button.builder(Component.literal("Set"), button ->
                Networking.INSTANCE.sendToServer(new PacketControlVideo(
                    be.getBlockPos(), "layout",
                    String.valueOf(layoutCols), String.valueOf(layoutRows)))
            ).bounds(rx, ry + 18, 42, 14).build());

            // Reset to 1×1
            addRenderableWidget(Button.builder(Component.literal("Reset"), button ->
                Networking.INSTANCE.sendToServer(new PacketControlVideo(be.getBlockPos(), "reset_layout"))
            ).bounds(rx + 46, ry + 18, 42, 14).build());
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
            guiGraphics.drawString(this.font, "Current: " + be.getCurrentVideoTitle(), leftPos + 10, topPos + 15, 0x404040, false);

            String status = be.isPlaying() ? "Playing" : "Stopped";
            guiGraphics.drawString(this.font, "Status: " + status, leftPos + 10, topPos + 130, 0x404040, false);

            // Volume display
            Display display = ClientVideoManager.getInstance().getDisplay(be.getBlockPos());
            if (display != null) {
                guiGraphics.drawString(this.font, "Vol: " + display.getVolume() + "%", leftPos + 95, topPos + 118, 0x404040, false);
            }

            // Screen layout info
            if (be.isMaster() && selectedCategory == null) {
                int rx = leftPos + 180;
                int ry = topPos + 125;
                guiGraphics.drawString(this.font, "W:" + layoutCols + " H:" + layoutRows, rx, ry - 10, 0x404040, false);
            } else if (!be.isMaster()) {
                guiGraphics.drawString(this.font, "Slave " + (be.getTileCol() + 1) + "x" + (be.getTileRow() + 1),
                    leftPos + 180, topPos + 130, 0x808080, false);
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
