package com.nestworld.video.client.gui;

import com.mojang.logging.LogUtils;
import com.nestworld.video.Category;
import com.nestworld.video.client.ClientData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public class VideoSelectionScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    public VideoSelectionScreen() {
        super(Component.literal("Select a Category")); // Заголовок тепер тут
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 40, 200, 20)
                .build());

        if (ClientData.getVideoConfig() == null || ClientData.getVideoConfig().getCategories().isEmpty()) {
            return;
        }

        int buttonHeight = 20;
        int buttonY = this.height / 2 - (ClientData.getVideoConfig().getCategories().size() * buttonHeight) / 2;

        for (Category category : ClientData.getVideoConfig().getCategories()) {
            Component buttonText = Component.literal(category.getName());
            addRenderableWidget(Button.builder(buttonText, button -> {
                        // ВИПРАВЛЕНО: Тепер ми відкриваємо новий екран
                        Minecraft.getInstance().setScreen(new VideoListScreen(category, this));
                    })
                    .bounds(this.width / 2 - 100, buttonY, 200, 20)
                    .build());
            buttonY += buttonHeight + 5;
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }
}