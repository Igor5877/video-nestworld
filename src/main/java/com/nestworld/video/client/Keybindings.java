package com.nestworld.video.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

public class Keybindings {
    public static final KeyMapping OPEN_MENU_KEY = new KeyMapping(
            "key.nestworldvideo.open_menu", // Унікальний ключ для перекладу
            KeyConflictContext.IN_GAME, // Кнопка буде працювати тільки в грі
            InputConstants.getKey(InputConstants.KEY_V, -1), // За замовчуванням - клавіша 'V'
            "key.category.nestworldvideo" // Назва категорії в налаштуваннях управління
    );
}