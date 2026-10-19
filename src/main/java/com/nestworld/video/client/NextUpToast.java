package com.nestworld.video.client;

import com.nestworld.video.CinemaMod;
import com.nestworld.video.client.gui.StarMenuPaint;
import com.nestworld.video.network.Networking;
import com.nestworld.video.network.PacketCancelNext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** "Next episode in 5 s" card in the corner of the HUD, with the key that cancels the auto-advance. */
@Mod.EventBusSubscriber(modid = CinemaMod.MOD_ID, value = Dist.CLIENT)
public final class NextUpToast {
    private NextUpToast() {}

    public static void cancel() {
        var next = ClientVideoManager.getInstance().getNextUp();
        if (next != null) Networking.INSTANCE.sendToServer(new PacketCancelNext(next.pos()));
    }

    public static String keyName() { return Keybindings.CANCEL_NEXT_KEY.getTranslatedKeyMessage().getString(); }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        var next = ClientVideoManager.getInstance().getNextUp();
        Minecraft mc = Minecraft.getInstance();
        if (next == null || mc.options.hideGui) return;
        var g = event.getGuiGraphics();
        var font = mc.font;
        String head = "Наступна серія за " + next.seconds() + " с";
        String title = font.plainSubstrByWidth(next.title(), 180);
        String hint = "[" + keyName() + "] Скасувати";
        int w = Math.max(Math.max(font.width(head), font.width(title)), font.width(hint)) + 22;
        int h = 42;
        int x = g.guiWidth() - w - 10, y = g.guiHeight() - h - 48;
        StarMenuPaint.round(g, x, y, w, h, 5, 0xE61D2A36);
        StarMenuPaint.round(g, x, y, 3, h, 1, 0xFFE7BE70);
        g.drawString(font, head, x + 11, y + 6, 0xFFE7BE70, false);
        g.drawString(font, title, x + 11, y + 18, 0xFFE8EDF2, false);
        g.drawString(font, hint, x + 11, y + 30, 0xFFAAB8C7, false);
        // The seconds left drain as a thin bar along the bottom edge.
        int fill = (int) ((w - 14) * Math.max(0, Math.min(1, next.seconds() / 5.0)));
        g.fill(x + 7, y + h - 3, x + 7 + fill, y + h - 2, 0xFFE7BE70);
    }
}
