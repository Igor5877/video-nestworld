package com.nestworld.video.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Flat, left-aligned rows keep long release names and episode numbers easy to scan.
 * {@code selected} marks the current tab/episode (gold accent), {@code primary} the main action (warm fill).
 */
public final class CinemaButton extends Button {
    private static final int GOLD = 0xFFE7BE70;
    public boolean selected, primary, centered;

    public CinemaButton(Component label, int x, int y, int w, OnPress action) {
        super(x,y,w,20,label,action,DEFAULT_NARRATION);
    }
    @Override public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
        boolean focus = isHoveredOrFocused();
        int fill = !active ? 0xFF25323E
                : primary ? (focus ? 0xFF7A6228 : 0xFF5F4D22)
                : selected ? (focus ? 0xFF4C6A86 : 0xFF3F5B77)
                : focus ? 0xFF48617A : 0xFF34495C;
        StarMenuPaint.round(g,getX(),getY(),width,height,4,fill);
        int strip = !active ? 0xFF45515B : (selected || primary || focus) ? GOLD : 0xFF6886A0;
        StarMenuPaint.round(g,getX(),getY(),2,height,1,strip);
        if (selected && active) StarMenuPaint.round(g,getX()+4,getY()+height-2,width-8,2,1,GOLD);
        var font = Minecraft.getInstance().font;
        String label = getMessage().getString();
        int color = !active ? 0xFF85919C : selected || primary ? 0xFFF7DE9D : 0xFFE8EDF2;
        if (centered || width <= 30) {
            g.drawString(font,label,getX()+(width-font.width(label))/2,getY()+6,color,false);
            return;
        }
        if (font.width(label) > width-14) label = font.plainSubstrByWidth(label,Math.max(1,width-22)) + "…";
        g.drawString(font,label,getX()+7,getY()+6,color,false);
    }
}
