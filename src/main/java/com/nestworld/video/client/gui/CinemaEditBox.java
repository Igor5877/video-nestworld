package com.nestworld.video.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Search field in the menu's own style: a rounded dark well with a gold border while focused, padded like the buttons. */
final class CinemaEditBox extends EditBox {
    private static final int PAD = 7;
    private final int fieldX, fieldY, fieldWidth, fieldHeight;

    /** @param x,y,width,height the whole visible field; the text area inside is inset. */
    CinemaEditBox(Font font, int x, int y, int width, int height, Component label) {
        super(font, x + PAD, y + (height - 8) / 2, width - 2 * PAD, 8, label);
        this.fieldX = x; this.fieldY = y; this.fieldWidth = width; this.fieldHeight = height;
        setBordered(false);
    }

    @Override public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
        boolean focused = isFocused();
        StarMenuPaint.round(g, fieldX, fieldY, fieldWidth, fieldHeight, 4, focused ? 0xFFE7BE70 : 0xFF48617A);
        StarMenuPaint.round(g, fieldX + 1, fieldY + 1, fieldWidth - 2, fieldHeight - 2, 3, 0xFF15212B);
        super.renderWidget(g, mx, my, partial);
    }

    /** The whole field is clickable, not only the thin text line. */
    @Override public boolean mouseClicked(double mx, double my, int button) {
        boolean inside = mx >= fieldX && mx < fieldX + fieldWidth && my >= fieldY && my < fieldY + fieldHeight;
        if (!inside) return super.mouseClicked(mx, my, button);
        double x = Math.max(getX(), Math.min(getX() + width - 1, mx));
        return super.mouseClicked(x, getY() + 1, button);
    }
}
