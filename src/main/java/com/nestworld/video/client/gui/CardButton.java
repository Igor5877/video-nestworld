package com.nestworld.video.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** A result card: poster on the left, the title (up to two lines) and a muted line with season/year/type next to it. */
final class CardButton extends Button {
    private static final int GOLD = 0xFFE7BE70;
    private String title = "", meta = "", poster = "";

    CardButton(int x, int y, int w, int h, OnPress action) {
        super(x, y, w, h, Component.empty(), action, DEFAULT_NARRATION);
    }

    void set(String title, String meta, String poster) {
        this.title = title == null ? "" : title;
        this.meta = meta == null ? "" : meta;
        this.poster = poster == null ? "" : poster;
        setMessage(Component.literal(this.title));
    }

    @Override public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
        boolean focus = isHoveredOrFocused();
        var font = Minecraft.getInstance().font;
        StarMenuPaint.round(g, getX(), getY(), width, height, 5, !active ? 0xFF25323E : focus ? 0xFF48617A : 0xFF34495C);
        StarMenuPaint.round(g, getX(), getY(), 2, height, 1, !active ? 0xFF45515B : focus ? GOLD : 0xFF6886A0);

        // Poster, fitted into a 2:3 slot with a darker well behind it.
        int slotH = height - 8, slotW = slotH * 2 / 3, px = getX() + 7, py = getY() + 4;
        StarMenuPaint.round(g, px, py, slotW, slotH, 3, 0xFF1E2B37);
        var image = PosterCache.get(poster);
        if (image != null && image.width() > 0 && image.height() > 0) {
            float scale = Math.min(slotW / (float) image.width(), slotH / (float) image.height());
            int w = Math.max(1, Math.round(image.width() * scale)), h = Math.max(1, Math.round(image.height() * scale));
            g.blit(image.location(), px + (slotW - w) / 2, py + (slotH - h) / 2, w, h, 0, 0,
                    image.width(), image.height(), image.width(), image.height());
        } else {
            String initial = title.isBlank() ? "?" : title.substring(0, 1).toUpperCase();
            g.drawString(font, initial, px + (slotW - font.width(initial)) / 2, py + (slotH - 8) / 2, 0xFF6F8294, false);
        }

        int textX = px + slotW + 8, maxWidth = getX() + width - 8 - textX;
        int titleColor = active ? 0xFFE8EDF2 : 0xFF85919C;
        List<FormattedCharSequence> lines = font.split(Component.literal(title), maxWidth);
        int shown = Math.min(2, lines.size());
        int y = getY() + 7;
        for (int i = 0; i < shown; i++) {
            if (i == 1 && lines.size() > 2) {
                String text = font.plainSubstrByWidth(title, maxWidth * 2);
                String second = text.length() > 0 ? font.plainSubstrByWidth(text.substring(Math.min(text.length(), cutAt(font, text, maxWidth))), maxWidth - 8) + "…" : "…";
                g.drawString(font, second, textX, y, titleColor, false);
            } else g.drawString(font, lines.get(i), textX, y, titleColor, false);
            y += 10;
        }
        if (!meta.isBlank()) g.drawString(font, font.plainSubstrByWidth(meta, maxWidth), textX, getY() + height - 14, 0xFF9AA9B8, false);
    }

    /** Number of leading characters of {@code text} that fit on one line of the given width. */
    private static int cutAt(net.minecraft.client.gui.Font font, String text, int width) {
        return font.plainSubstrByWidth(text, width).length();
    }
}
