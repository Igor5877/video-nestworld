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
                "Авто: спочатку VLC, а якщо він не показує картинку, FFmpeg.\nFFmpeg працює однаково на всіх системах (потрібне завантаження бібліотек один раз).");
        choice("Максимальна якість", cellW, () -> { int h = NestworldConfig.MAX_VIDEO_HEIGHT.get(); return h == 0 ? "Авто (найкраща)" : h + "p"; },
                () -> { cycleInt(NestworldConfig.MAX_VIDEO_HEIGHT, QUALITIES); restartStreams = true; },
                "Обмеження висоти відео для потоків з кількома якостями.\nНижча якість — менше навантаження на мережу й процесор.");
        choice("Режим відображення", cellW, () -> switch (NestworldConfig.RENDER_MODE.get()) {
                    case "vanilla_pipeline" -> "Конвеєр гри"; case "direct" -> "Напряму"; default -> "Авто"; },
                () -> cycleString(NestworldConfig.RENDER_MODE, List.of("auto", "vanilla_pipeline", "direct")),
                "Авто перемикається на конвеєр гри, коли ввімкнено шейдери (Iris/Oculus).");
        toggle("Згладжування (бікубічне)", cellW, NestworldConfig.SMOOTH_SCALING,
                "Згладжує сходинки на великих екранах. Вимкніть, якщо відеокарта не тягне.");
        choice("Збільшення картинки", cellW, () -> switch (NestworldConfig.UPSCALER.get()) { case "bicubic" -> "Бікубічне"; case "anime4k" -> "Anime4K (тест)"; default -> "Слідує за лініями"; },
                () -> cycleString(NestworldConfig.UPSCALER, List.of("edge", "bicubic", "anime4k")),
                "Як збільшується відео, коли екран більший за нього. Не діє з шейдерами.");
        slider("Різкість", cellW, 100, 0, NestworldConfig.SHARPNESS::get, NestworldConfig.SHARPNESS::set,
                "Додаткове підвищення різкості. Не діє з шейдерами.");
        toggle("Мипмапи (дальні екрани)", cellW, NestworldConfig.MIPMAPS,
                "Менше мерехтіння далеких екранів (лише для FFmpeg).");
        toggle("Просторовий звук", cellW, NestworldConfig.SPATIAL_AUDIO,
                "Звук іде з екрана в 3D (лише для FFmpeg). Діє для потоків, запущених після зміни.");
        slider("М’якість звуку", cellW, 100, 0, NestworldConfig.SPATIAL_SOFTNESS::get, NestworldConfig.SPATIAL_SOFTNESS::set,
                "0 — широка панорама, 100 — майже моно.");
        toggle("Авто-VSync біля екрана", cellW, NestworldConfig.AUTO_VSYNC,
                "Вмикає вертикальну синхронізацію, лише коли ви дивитеся на екран, щоб не було смуг. Ваше налаштування не змінюється.");
        choice("Макс. відстань потоку", cellW, () -> NestworldConfig.MAX_STREAM_DISTANCE.get() + " бл.",
                () -> cycleInt(NestworldConfig.MAX_STREAM_DISTANCE, DISTANCES),
                "Далі за цю відстань відео не завантажується.");
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
            value.set(!value.get()); self[0].setMessage(Component.literal(text.get())); self[0].selected = value.get();
        });
        self[0].selected = value.get();
        add(label, self[0], hint);
    }
    private void slider(String label, int w, int max, int mark, IntSupplier get, IntConsumer set, String hint) {
        add(label, new CinemaSlider(0, 0, w, max, mark, get, v -> set.accept(Math.max(0, Math.min(max, v)))), hint);
    }
    private static void cycleString(net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> value, List<String> options) {
        value.set(options.get((options.indexOf(value.get()) + 1) % options.size()));
    }
    private static void cycleInt(net.minecraftforge.common.ForgeConfigSpec.IntValue value, int[] options) {
        int next = options[0];
        for (int i = 0; i < options.length; i++) if (options[i] == value.get()) next = options[(i + 1) % options.length];
        value.set(next);
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
