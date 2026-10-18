package com.nestworld.video.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Flat, left-aligned rows keep long release names and episode numbers easy to scan. */
final class CinemaButton extends Button {
    CinemaButton(Component label, int x, int y, int w, OnPress action) {
        super(x,y,w,20,label,action,DEFAULT_NARRATION);
    }
    @Override public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
        boolean focus = isHoveredOrFocused();
        StarMenuPaint.round(g,getX(),getY(),width,height,4,
                active ? focus ? 0xFF48617A : 0xFF34495C : 0xFF25323E);
        StarMenuPaint.round(g,getX(),getY(),2,height,1,
                active ? focus ? 0xFFE7BE70 : 0xFF6886A0 : 0xFF45515B);
        var font = Minecraft.getInstance().font;
        String label = getMessage().getString();
        if (width <= 30) {
            g.drawString(font,label,getX()+(width-font.width(label))/2,getY()+6,active ? 0xFFE8EDF2 : 0xFF85919C,false);
            return;
        }
        if (font.width(label) > width-14) label = font.plainSubstrByWidth(label,Math.max(1,width-22)) + "…";
        g.drawString(font,label,getX()+7,getY()+6,active ? 0xFFE8EDF2 : 0xFF85919C,false);
    }
}
