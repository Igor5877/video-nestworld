package com.nestworld.video.client;

import com.nestworld.video.CinemaMod;
import com.nestworld.video.client.gui.CinemaButton;
import com.nestworld.video.client.gui.NestworldSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Adds a "Nestworld Video" button to the game's video settings (vanilla, and Embeddium/Sodium's replacement). */
@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, value = Dist.CLIENT)
public final class SettingsEntryPoints {
    private SettingsEntryPoints() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        String name = screen.getClass().getName();
        boolean sodium = name.endsWith("SodiumOptionsGUI") || name.endsWith("EmbeddiumOptionsGUI");
        if (!(screen instanceof VideoSettingsScreen) && !sodium) return;
        var open = new CinemaButton(Component.literal("Nestworld Video ⚙"), 0, 0, 120,
                b -> Minecraft.getInstance().setScreen(new NestworldSettingsScreen(screen)));
        open.centered = true;
        if (sodium) { open.setX(screen.width / 2 - 60); open.setY(8); }       // free strip above the search field
        else { open.setX(screen.width - 126); open.setY(6); }                 // top-right corner, clear of the title
        event.addListener(open);
    }
}
