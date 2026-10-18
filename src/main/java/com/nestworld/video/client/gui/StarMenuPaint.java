package com.nestworld.video.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Small adaptation of the rounded panel and shadow primitives from
 * Star Menu Standalone's MenuPaint (MIT, Copyright (c) 2026 Star Menu Standalone contributors).
 */
final class StarMenuPaint {
    private StarMenuPaint() {}

    static void panel(GuiGraphics g, int x, int y, int w, int h, int fill, int accent) {
        for (int spread = 8; spread >= 2; spread -= 2) {
            round(g, x - spread, y - spread + 3, w + spread * 2, h + spread * 2,
                    9 + spread, 0x12000000);
        }
        round(g, x, y, w, h, 8, fill);
        if (accent != 0) round(g, x + 8, y, w - 16, 2, 1, accent);
    }

    static void round(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        g.fill(x + r, y, x + w - r, y + h, color);
        for (int row = 0; row < r; row++) {
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - (r - row - 0.5) * (r - row - 0.5)));
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
            g.fill(x + inset, y + h - row - 1, x + w - inset, y + h - row, color);
        }
    }
}
