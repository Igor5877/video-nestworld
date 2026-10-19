package com.nestworld.video.client.integration;

import com.mojang.logging.LogUtils;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.client.ClientVideoManager;
import com.google.common.collect.ImmutableList;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Adds a "Nestworld Video" page to Embeddium's video settings (the screen behind Esc → Video Settings), so the mod's
 * options sit next to the game's own. Only touched when Embeddium is installed; any failure just leaves the
 * fallback button in place (see SettingsEntryPoints).
 */
public final class EmbeddiumOptions {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private EmbeddiumOptions() {}

    public static void register() {
        try {
            OptionGUIConstructionEvent.BUS.addListener(event -> {
                try { event.addPage(buildPage()); }
                catch (Throwable t) { EmbeddiumOptionsState.set(false); LOGGER.warn("Nestworld Video: could not add its Embeddium settings page", t); }
            });
            EmbeddiumOptionsState.set(true);
        } catch (Throwable t) {
            LOGGER.warn("Nestworld Video: Embeddium settings integration unavailable ({})", t.toString());
        }
    }

    private enum Decoder { AUTO("auto", "Авто (VLC, потім FFmpeg)"), FFMPEG("ffmpeg", "FFmpeg (власний)"), WATERMEDIA("watermedia", "VLC (WaterMedia)");
        final String key, label; Decoder(String key, String label) { this.key = key; this.label = label; }
        static Decoder of(String key) { for (var d : values()) if (d.key.equals(key)) return d; return AUTO; } }
    private enum Quality { AUTO(0, "Авто (найкраща)"), P1080(1080, "1080p"), P720(720, "720p"), P480(480, "480p"), P360(360, "360p");
        final int height; final String label; Quality(int height, String label) { this.height = height; this.label = label; }
        static Quality of(int height) { for (var q : values()) if (q.height == height) return q; return AUTO; } }
    private enum RenderMode { AUTO("auto", "Авто"), PIPELINE("vanilla_pipeline", "Конвеєр гри"), DIRECT("direct", "Напряму");
        final String key, label; RenderMode(String key, String label) { this.key = key; this.label = label; }
        static RenderMode of(String key) { for (var m : values()) if (m.key.equals(key)) return m; return AUTO; } }
    private enum Upscaler { EDGE("edge", "Слідує за лініями"), BICUBIC("bicubic", "Бікубічне"), ANIME4K("anime4k", "Anime4K (тест)");
        final String key, label; Upscaler(String key, String label) { this.key = key; this.label = label; }
        static Upscaler of(String key) { for (var u : values()) if (u.key.equals(key)) return u; return EDGE; } }

    /** Values are written to the config immediately; streams restart on Apply when a change needs it. */
    private static final class Storage implements OptionStorage<Storage> {
        boolean restartStreams;
        @Override public Storage getData() { return this; }
        @Override public void save() {
            NestworldConfig.CLIENT_SPEC.save();
            if (restartStreams) { restartStreams = false; ClientVideoManager.getInstance().restartAll(); }
        }
    }

    /** Without WaterMedia there is no VLC, so the decoder choice would be meaningless: FFmpeg is always used. */
    private static OptionGroup playbackGroup(OptionImpl<Storage, Decoder> decoder, OptionImpl<Storage, Quality> quality, OptionImpl<Storage, RenderMode> mode) {
        var group = OptionGroup.createBuilder();
        if (net.minecraftforge.fml.ModList.get().isLoaded("watermedia")) group.add(decoder);
        return group.add(quality).add(mode).build();
    }

    private static OptionPage buildPage() {
        Storage storage = new Storage();
        var decoder = OptionImpl.createBuilder(Decoder.class, storage)
                .setName(Component.literal("Декодер відео"))
                .setTooltip(Component.literal("Авто: спочатку VLC, а якщо він не показує картинку, FFmpeg. FFmpeg працює однаково на всіх системах."))
                .setControl(o -> new CyclingControl<>(o, Decoder.class, names(Decoder.values(), d -> d.label)))
                .setBinding((s, v) -> { NestworldConfig.DECODER.set(v.key); s.restartStreams = true; }, s -> Decoder.of(NestworldConfig.DECODER.get()))
                .build();
        var quality = OptionImpl.createBuilder(Quality.class, storage)
                .setName(Component.literal("Максимальна якість"))
                .setTooltip(Component.literal("Обмеження висоти відео для потоків з кількома якостями. Нижча якість — менше навантаження."))
                .setImpact(OptionImpact.HIGH)
                .setControl(o -> new CyclingControl<>(o, Quality.class, names(Quality.values(), q -> q.label)))
                .setBinding((s, v) -> { NestworldConfig.MAX_VIDEO_HEIGHT.set(v.height); s.restartStreams = true; }, s -> Quality.of(NestworldConfig.MAX_VIDEO_HEIGHT.get()))
                .build();
        var mode = OptionImpl.createBuilder(RenderMode.class, storage)
                .setName(Component.literal("Режим відображення"))
                .setTooltip(Component.literal("Авто перемикається на конвеєр гри, коли ввімкнені шейдери (Iris/Oculus)."))
                .setControl(o -> new CyclingControl<>(o, RenderMode.class, names(RenderMode.values(), m -> m.label)))
                .setBinding((s, v) -> NestworldConfig.RENDER_MODE.set(v.key), s -> RenderMode.of(NestworldConfig.RENDER_MODE.get()))
                .build();

        var smooth = tick(storage, "Згладжування (бікубічне)", "Згладжує сходинки на великих екранах. Вимкніть, якщо відеокарта не тягне.", NestworldConfig.SMOOTH_SCALING);
        var upscaler = OptionImpl.createBuilder(Upscaler.class, storage)
                .setName(Component.literal("Збільшення картинки"))
                .setTooltip(Component.literal("Як збільшується відео, коли екран більший за нього. Не діє з шейдерами."))
                .setControl(o -> new CyclingControl<>(o, Upscaler.class, names(Upscaler.values(), u -> u.label)))
                .setBinding((s, v) -> NestworldConfig.UPSCALER.set(v.key), s -> Upscaler.of(NestworldConfig.UPSCALER.get()))
                .build();
        var sharp = slider(storage, "Різкість", "Додаткове підвищення різкості. Не діє з шейдерами.", NestworldConfig.SHARPNESS, 0, 100, 5, ControlValueFormatter.percentage());
        var mips = tick(storage, "Мипмапи (дальні екрани)", "Менше мерехтіння далеких екранів (лише для FFmpeg).", NestworldConfig.MIPMAPS);

        var spatial = tick(storage, "Просторовий звук", "Звук іде з екрана в 3D (лише для FFmpeg). Діє для потоків, запущених після зміни.", NestworldConfig.SPATIAL_AUDIO);
        var soft = slider(storage, "М’якість звуку", "0 — широка панорама, 100 — майже моно.", NestworldConfig.SPATIAL_SOFTNESS, 0, 100, 5, ControlValueFormatter.percentage());
        var vsync = tick(storage, "Авто-VSync біля екрана", "Вмикає вертикальну синхронізацію, лише коли ви дивитеся на екран, щоб не було смуг. Ваше налаштування VSync не змінюється.", NestworldConfig.AUTO_VSYNC);

        var distance = slider(storage, "Макс. відстань потоку", "Далі за цю відстань відео не завантажується.", NestworldConfig.MAX_STREAM_DISTANCE, 16, 512, 16, ControlValueFormatter.number());
        var streams = slider(storage, "Макс. кількість потоків", "Скільки відео одночасно може декодувати ваш клієнт.", NestworldConfig.MAX_ACTIVE_STREAMS, 1, 32, 1, ControlValueFormatter.number());
        var debug = tick(storage, "Статистика в журналі", "Пише в latest.log швидкість годинника, черги й розсинхрон. Для пошуку проблем.", NestworldConfig.DEBUG_STATS);

        return new OptionPage(Component.literal("Nestworld Video"), ImmutableList.<OptionGroup>of(
                playbackGroup(decoder, quality, mode),
                OptionGroup.createBuilder().add(smooth).add(upscaler).add(sharp).add(mips).build(),
                OptionGroup.createBuilder().add(spatial).add(soft).add(vsync).build(),
                OptionGroup.createBuilder().add(distance).add(streams).add(debug).build()));
    }

    private static <E> Component[] names(E[] values, Function<E, String> label) {
        Component[] names = new Component[values.length];
        for (int i = 0; i < values.length; i++) names[i] = Component.literal(label.apply(values[i]));
        return names;
    }

    private static OptionImpl<Storage, Boolean> tick(Storage storage, String name, String tooltip, ForgeConfigSpec.BooleanValue value) {
        BiConsumer<Storage, Boolean> set = (s, v) -> value.set(v);
        return OptionImpl.createBuilder(Boolean.class, storage)
                .setName(Component.literal(name)).setTooltip(Component.literal(tooltip))
                .setControl(TickBoxControl::new)
                .setBinding(set, s -> value.get())
                .build();
    }

    private static OptionImpl<Storage, Integer> slider(Storage storage, String name, String tooltip, ForgeConfigSpec.IntValue value,
                                                       int min, int max, int step, ControlValueFormatter format) {
        BiConsumer<Storage, Integer> set = (s, v) -> value.set(v);
        return OptionImpl.createBuilder(Integer.class, storage)
                .setName(Component.literal(name)).setTooltip(Component.literal(tooltip))
                .setControl(o -> new SliderControl(o, min, max, step, format))
                .setBinding(set, s -> value.get())
                .build();
    }
}
