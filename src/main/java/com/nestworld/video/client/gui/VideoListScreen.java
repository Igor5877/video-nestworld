package com.nestworld.video.client.gui;

import com.mojang.logging.LogUtils;
import com.nestworld.video.Category;
import com.nestworld.video.Video;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public class VideoListScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final Category category;
    private final Screen parent;

    public VideoListScreen(Category category, Screen parent) {
        super(Component.literal("Select a Video"));
        this.category = category;
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Back button
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 40, 200, 20)
                .build());

        if (category.getVideos() == null || category.getVideos().isEmpty()) {
            return;
        }

        int buttonHeight = 20;
        int buttonY = this.height / 2 - (category.getVideos().size() * buttonHeight) / 2;

        for (Video video : category.getVideos()) {
            Component buttonText = Component.literal(video.getTitle());
            addRenderableWidget(Button.builder(buttonText, button -> {
                        LOGGER.info("Selected video: {}. URL: {}. Cannot play from this screen.", video.getTitle(), video.getUrl());
                        // We can't do anything here as we don't have a BlockPos
                    })
                    .bounds(this.width / 2 - 100, buttonY, 200, 20)
                    .build());
            buttonY += buttonHeight + 5;
        }
    }
    
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }
}
