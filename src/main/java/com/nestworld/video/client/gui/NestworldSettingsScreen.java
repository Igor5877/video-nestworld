package com.nestworld.video.client.gui;

import com.nestworld.video.NestworldConfig;
import com.nestworld.video.client.ClientVideoManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The player's own settings of the mod (decoder, quality, picture, sound), reachable from the game's video settings,
 * the mod list and the cinema menu. Everything is stored in the client config file.
 */
public final class NestworldSettingsScreen extends Screen {
    private static final int TEXT = 0xE8EDF2, MUTED = 0xAAB8C7, GOLD = 0xE7BE70, CELL = 40;
    private final Screen parent;
    private record Cell(String label, AbstractWidget widget) {}
    private final List<Cell> cells = new ArrayList<>();
    private boolean restartStreams;
    private int left, top, panelW, panelH, scroll, columns, viewTop, viewBottom;

    public NestworldSettingsScreen(Screen parent) {
        super(Component.literal("Nestworld · Налаштування відео"));
        this.parent = parent;
    }

    private static final int[] QUALITIES = {0, 1080, 720, 480, 360};
    private static final int[] DISTANCES = {32, 64, 128, 256, 512};
    private static final int[] STREAMS = {2, 4, 8, 16, 32};
    private static final int[] PLAYING_SCREENS = {1, 2, 3, 4, 6, 8};

    @Override protected void init() {
        cells.clear();
        panelW = Math.min(470, width - 12); panelH = Math.min(420, height - 12);
        left = (width - panelW) / 2; top = (height - panelH) / 2;
        columns = panelW >= 380 ? 2 : 1;
        viewTop = top + 36; viewBottom = top + panelH - 34;
        int cellW = (panelW - 24 - (columns - 1) * 8) / columns;

        // Without WaterMedia there is no VLC and FFmpeg is always used, so the choice is hidden.
        if (net.minecraftforge.fml.ModList.get().isLoaded("watermedia")) choice("Декодер відео", cellW, () -> switch (NestworldConfig.DECODER.get()) {
                    case "ffmpeg" -> "FFmpeg (власний)"; case "watermedia" -> "VLC (WaterMedia)"; default -> "Авто"; },
                () -> { cycleString(NestworldConfig.DECODER, List.of("auto", "ffmpeg", "watermedia")); restartStreams = true; },
                "Авто: FFmpeg, а якщо він не може відкрити відео, VLC.\nЛише FFmpeg плавно тримає синхронізацію з іншими глядачами, кешує потік (миттєва перемотка) і дає світло екрана. Бібліотеки завантажуються один раз.");
        choice("Максимальна якість", cellW, () -> { int h = NestworldConfig.MAX_VIDEO_HEIGHT.get(); return h == 0 ? "Авто (найкраща)" : h + "p"; },
                () -> { cycleInt(NestworldConfig.MAX_VIDEO_HEIGHT, QUALITIES); restartStreams = true; },
                "Обмеження висоти відео для потоків з кількома якостями.\nНижча якість — менше навантаження на мережу й процесор.");
        choice("Режим відображення", cellW, () -> switch (NestworldConfig.RENDER_MODE.get()) {
                    case "vanilla_pipeline" -> "Конвеєр гри"; case "direct" -> "Напряму"; default -> "Авто"; },
                () -> cycleString(NestworldConfig.RENDER_MODE, List.of("auto", "vanilla_pipeline", "direct")),
                "Авто перемикається на конвеєр гри, коли ввімкнено шейдери (Iris/Oculus).");
        toggle("Масштабування шейдером", cellW, NestworldConfig.SMOOTH_SCALING,
                "Шейдер масштабування мода: збільшення картинки (лініями / бікубічне / Anime4K) і різкість. З шейдерами кадр збільшується вдвічі перед тим, як його малює шейдер-пак, — це додаткове навантаження. Вимкніть, якщо відеокарта не тягне.");
        choice("Збільшення картинки", cellW, () -> switch (NestworldConfig.UPSCALER.get()) { case "bicubic" -> "Бікубічне"; case "anime4k" -> "Anime4K (тест)"; default -> "Слідує за лініями"; },
                () -> cycleString(NestworldConfig.UPSCALER, List.of("edge", "bicubic", "anime4k")),
                "Як збільшується відео, коли екран більший за нього (потрібне «Масштабування шейдером»).");
        slider("Різкість", cellW, 100, 0, NestworldConfig.SHARPNESS::get, NestworldConfig.SHARPNESS::set,
                "Додаткове підвищення різкості (потрібне «Масштабування шейдером»).");
        toggle("Мипмапи (дальні екрани)", cellW, NestworldConfig.MIPMAPS,
                "Менше мерехтіння далеких екранів (FFmpeg; з шейдерами — і для збільшеної картинки).");
        toggle("Просторовий звук", cellW, NestworldConfig.SPATIAL_AUDIO,
                "Звук іде з екрана в 3D (лише для FFmpeg). Діє для потоків, запущених після зміни.");
        slider("М’якість звуку", cellW, 100, 0, NestworldConfig.SPATIAL_SOFTNESS::get, NestworldConfig.SPATIAL_SOFTNESS::set,
                "0 — широка панорама, 100 — майже моно.");
        choice("Колірне світло екрана", cellW, () -> NestworldConfig.SCREEN_GLOW.get() > 0 ? "Увімкнено" : "Вимкнено",
                () -> safely(() -> NestworldConfig.SCREEN_GLOW.set(NestworldConfig.SCREEN_GLOW.get() > 0 ? 0 : 2)),
                "Екран підсвічує підлогу й стіни кольорами картинки: темний кадр світить слабко, світлий сильно. Лише з запатченим BSL (Oculus/Iris); тонке налаштування — у налаштуваннях шейдера, розділ Camera.");
        slider("Яскравість кольорового світла", cellW, 100, 0, NestworldConfig.SCREEN_GLOW_STRENGTH::get, NestworldConfig.SCREEN_GLOW_STRENGTH::set,
                "Наскільки яскраво екран підсвічує кімнату, 80 % типово. Решта налаштувань світла — у шейдері: Camera → Video Screen Light.");
        toggle("Акустика приміщення", cellW, NestworldConfig.ACOUSTICS,
                "Стіни приглушують звук екрана, двері й отвори пропускають його, у залах з’являється луна (лише FFmpeg і просторовий звук).");
        slider("Луна приміщення", cellW, 100, 0, NestworldConfig.ACOUSTICS_REVERB::get, NestworldConfig.ACOUSTICS_REVERB::set,
                "0 — лише приглушення стінами. Звукова доріжка вже може містити власну луну.");
        slider("Приглушення стінами", cellW, 200, 100, NestworldConfig.ACOUSTICS_MUFFLE::get, NestworldConfig.ACOUSTICS_MUFFLE::set,
                "Наскільки сильно стіни приглушують звук (100 % типово). Більше значення робить різницю між матеріалами виразнішою.");
        choice("Якість акустики", cellW, () -> switch (NestworldConfig.ACOUSTICS_QUALITY.get()) { case 1 -> "Швидка"; case 3 -> "Детальна"; default -> "Збалансована"; },
                () -> safely(() -> NestworldConfig.ACOUSTICS_QUALITY.set(NestworldConfig.ACOUSTICS_QUALITY.get() % 3 + 1)),
                "Кількість променів і відбиттів. Детальна точніша, але важча для процесора.");
        toggle("Авто-VSync біля екрана", cellW, NestworldConfig.AUTO_VSYNC,
                "Вмикає вертикальну синхронізацію, лише коли ви дивитеся на екран, щоб не було смуг. Ваше налаштування не змінюється.");
        choice("Макс. відстань потоку", cellW, () -> NestworldConfig.MAX_STREAM_DISTANCE.get() + " бл.",
                () -> cycleInt(NestworldConfig.MAX_STREAM_DISTANCE, DISTANCES),
                "Далі за цю відстань відео не завантажується.");
        choice("Екранів одночасно", cellW, () -> String.valueOf(NestworldConfig.DECODE_SCREENS.get()),
                () -> cycleInt(NestworldConfig.DECODE_SCREENS, PLAYING_SCREENS),
                "Скільки екранів відтворюються одночасно: той, на який дивитеся, і найближчі. Інші показують останній кадр і нічого не завантажують, доки ви до них не повернетеся.");
        choice("Макс. кількість потоків", cellW, () -> String.valueOf(NestworldConfig.MAX_ACTIVE_STREAMS.get()),
                () -> cycleInt(NestworldConfig.MAX_ACTIVE_STREAMS, STREAMS),
                "Скільки відео одночасно може декодувати ваш клієнт.");
        toggle("Статистика в журналі", cellW, NestworldConfig.DEBUG_STATS,
                "Пише в latest.log швидкість годинника, черги, розсинхрон. Для пошуку проблем.");

        addRenderableWidget(new CinemaButton(Component.literal("Готово"), left + panelW / 2 - 60, top + panelH - 28, 120, b -> onClose()));
        var done = (CinemaButton) children().get(children().size() - 1);
        done.primary = true; done.centered = true;
        layoutCells();
    }

    private void layoutCells() {
        int cellW = (panelW - 24 - (columns - 1) * 8) / columns;
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            int col = i % columns, row = i / columns;
            cell.widget().setX(left + 12 + col * (cellW + 8));
            cell.widget().setY(viewTop + row * CELL + 14 - scroll);
            cell.widget().visible = cell.widget().getY() >= viewTop + 8 && cell.widget().getY() + 20 <= viewBottom + 8;
        }
    }
    private int maxScroll() {
        int rows = (cells.size() + columns - 1) / columns;
        return Math.max(0, rows * CELL - (viewBottom - viewTop));
    }

    private void add(String label, AbstractWidget widget, String hint) {
        widget.setTooltip(Tooltip.create(Component.literal(hint)));
        cells.add(new Cell(label, widget));
        addRenderableWidget(widget);
    }
    private void choice(String label, int w, Supplier<String> text, Runnable next, String hint) {
        CinemaButton[] self = new CinemaButton[1];
        self[0] = new CinemaButton(Component.literal(text.get()), 0, 0, w, b -> { next.run(); self[0].setMessage(Component.literal(text.get())); });
        add(label, self[0], hint);
    }
    private void toggle(String label, int w, net.minecraftforge.common.ForgeConfigSpec.BooleanValue value, String hint) {
        Supplier<String> text = () -> value.get() ? "Увімкнено" : "Вимкнено";
        CinemaButton[] self = new CinemaButton[1];
        self[0] = new CinemaButton(Component.literal(text.get()), 0, 0, w, b -> {
            safely(() -> value.set(!value.get())); self[0].setMessage(Component.literal(text.get())); self[0].selected = value.get();
        });
        self[0].selected = value.get();
        add(label, self[0], hint);
    }
    private void slider(String label, int w, int max, int mark, IntSupplier get, IntConsumer set, String hint) {
        add(label, new CinemaSlider(0, 0, w, max, mark, get, v -> safely(() -> set.accept(Math.max(0, Math.min(max, v)))), true), hint);
    }
    /**
     * Forge saves the config file on every set while its file watcher may reload it on another thread; if the two collide
     * the set throws a ConcurrentModificationException. Retry once instead of crashing the game.
     */
    private static void safely(Runnable write) {
        try { write.run(); }
        catch (java.util.ConcurrentModificationException first) {
            try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { write.run(); }
            catch (java.util.ConcurrentModificationException second) { com.mojang.logging.LogUtils.getLogger().warn("Nestworld Video: the setting could not be saved right now, try again"); }
        }
    }
    private static void cycleString(net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> value, List<String> options) {
        safely(() -> value.set(options.get((options.indexOf(value.get()) + 1) % options.size())));
    }
    private static void cycleInt(net.minecraftforge.common.ForgeConfigSpec.IntValue value, int[] options) {
        int next = options[0];
        for (int i = 0; i < options.length; i++) if (options[i] == value.get()) next = options[(i + 1) % options.length];
        int chosen = next;
        safely(() -> value.set(chosen));
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (delta * 20)));
        layoutCells();
        return true;
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        StarMenuPaint.panel(g, left, top, panelW, panelH, 0xFA1D2A36, 0xFFE7BE70);
        g.drawString(font, "Nestworld · Налаштування відео", left + 12, top + 12, TEXT, false);
        int cellW = (panelW - 24 - (columns - 1) * 8) / columns;
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            if (!cell.widget().visible) continue;
            g.drawString(font, font.plainSubstrByWidth(cell.label(), cellW), cell.widget().getX(), cell.widget().getY() - 11, MUTED, false);
        }
        if (maxScroll() > 0) g.drawString(font, "▾ прокрутіть колесом миші", left + panelW - 12 - font.width("▾ прокрутіть колесом миші"), top + 12, MUTED, false);
        super.render(g, mx, my, partial);
    }

    @Override public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override public void removed() {
        NestworldConfig.CLIENT_SPEC.save();
        // Some changes only take effect for streams opened afterwards.
        if (restartStreams) ClientVideoManager.getInstance().restartAll();
        restartStreams = false;
        super.removed();
    }
}
