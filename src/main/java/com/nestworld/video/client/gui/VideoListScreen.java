package com.nestworld.video.client.gui;

import com.mojang.logging.LogUtils;
import com.nestworld.video.Category;
import com.nestworld.video.Video;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public class VideoListScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final Category category;

    public VideoListScreen(Category category) {
        // Ми передаємо вибрану категорію на цей екран
        super(Component.literal(category.getName()));
        this.category = category;
    }

    @Override
    protected void init() {
        // Кнопка "Назад", яка повертає нас на попередній екран
        addRenderableWidget(Button.builder(Component.literal("Back"),
                        button -> this.minecraft.setScreen(new VideoSelectionScreen()))
                .bounds(this.width / 2 - 100, this.height - 40, 200, 20)
                .build());

        if (this.category.getVideos() == null || this.category.getVideos().isEmpty()) {
            return;
        }

        // Створюємо кнопки для кожного відео в цій категорії
        int buttonHeight = 20;
        int buttonY = this.height / 2 - (this.category.getVideos().size() * buttonHeight) / 2;

        for (Video video : this.category.getVideos()) {
            Component buttonText = Component.literal(video.getTitle());
            addRenderableWidget(Button.builder(buttonText, button -> {
                        // Поки що кнопка просто логує назву відео
                        // У майбутньому тут буде відкриття екрану з деталями відео
                        LOGGER.info("Clicked video: {}", video.getTitle());
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