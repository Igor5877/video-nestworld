package com.nestworld.video.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Horizontal drag slider in the cinema style: percentage above a gold-filled track. */
public final class CinemaSlider extends AbstractWidget {
    private final int max;
    private final IntSupplier value;
    private final IntConsumer onChange;
    /** Value that is drawn as a small tick on the track (e.g. 100% = original loudness). */
    private final int mark;

    /**
     * When set, the value is only handed over when the mouse is released. Used for sliders that write a config file:
     * a write for every pixel of a drag races with Forge's config file watcher (it reloads the file on another thread)
     * and can crash with a ConcurrentModificationException.
     */
    private final boolean commitOnRelease;
    private Integer pending;

    public CinemaSlider(int x, int y, int width, int max, int mark, IntSupplier value, IntConsumer onChange) {
        this(x, y, width, max, mark, value, onChange, false);
    }

    public CinemaSlider(int x, int y, int width, int max, int mark, IntSupplier value, IntConsumer onChange, boolean commitOnRelease) {
        super(x, y, width, 20, Component.empty());
        this.max = max; this.mark = mark; this.value = value; this.onChange = onChange; this.commitOnRelease = commitOnRelease;
    }

    private void apply(double mouseX) {
        double fraction = Math.max(0, Math.min(1, (mouseX - (getX() + 3)) / (double) (width - 6)));
        int target = (int) Math.round(fraction * max / 5.0) * 5;
        if (commitOnRelease) pending = target; else onChange.accept(target);
    }
    @Override public void onClick(double mouseX, double mouseY) { apply(mouseX); }
    @Override protected void onDrag(double mouseX, double mouseY, double dx, double dy) { apply(mouseX); }
    @Override public void onRelease(double mouseX, double mouseY) {
        if (pending != null) { int target = pending; pending = null; onChange.accept(target); }
    }
    @Override public void playDownSound(net.minecraft.client.sounds.SoundManager manager) {}

    @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int v = Math.max(0, Math.min(max, pending != null ? pending : value.getAsInt()));
        int trackY = getY() + 13, inner = width - 6;
        boolean hot = active && (isHoveredOrFocused());
        String label = active ? v + "%" : "—";
        var font = net.minecraft.client.Minecraft.getInstance().font;
        g.drawString(font, label, getX() + (width - font.width(label)) / 2, getY() + 2, active ? 0xFFE8EDF2 : 0xFF6B7885, false);
        StarMenuPaint.round(g, getX() + 3, trackY, inner, hot ? 5 : 4, 2, 0xFF34495C);
        if (!active) return;
        int filled = (int) (inner * (v / (double) max));
        if (filled > 0) StarMenuPaint.round(g, getX() + 3, trackY, Math.max(3, filled), hot ? 5 : 4, 2, 0xFFE7BE70);
        int markX = getX() + 3 + (int) (inner * (mark / (double) max));
        if (mark > 0 && mark < max) g.fill(markX, trackY - 2, markX + 1, trackY + 6, 0xFF8FA1B3);
        StarMenuPaint.round(g, getX() + 3 + filled - 2, trackY - 3, 5, 10, 2, hot ? 0xFFF7DE9D : 0xFFE8EDF2);
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
