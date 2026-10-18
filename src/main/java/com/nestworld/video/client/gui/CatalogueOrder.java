package com.nestworld.video.client.gui;

import com.nestworld.video.Video;
import java.util.Comparator;
import java.util.Locale;

public final class CatalogueOrder {
    private CatalogueOrder() {}
    public static String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    public static boolean matches(String title, String query) { return normalize(title).contains(normalize(query)); }
    public static final Comparator<Video> EPISODES = Comparator.comparingDouble((Video v) -> number(v.getEpisode()))
            .thenComparing(v -> v.getTitle() == null ? "" : v.getTitle());
    private static double number(String value) {
        try { return Double.parseDouble(value.replace(',', '.')); } catch (Exception e) { return Double.MAX_VALUE; }
    }
}
