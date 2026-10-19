package com.nestworld.video.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.nestworld.video.Category;
import com.nestworld.video.Video;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * What this player has watched: progress and "watched" flags per episode, the quality it was last played in, and
 * favourite releases. Stored client-side next to the downloaded decoder libraries. Episodes are keyed by
 * release + voice + episode number (not by URL), so a refreshed stream link does not lose the progress.
 */
public final class WatchHistory {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Under this much progress an episode is not offered for resuming; past this fraction it counts as watched. */
    public static final long MIN_RESUME_MS = 15_000;
    public static final double WATCHED_FRACTION = 0.92;

    public static final class Entry {
        public String category = "", dub = "", episode = "", title = "";
        public long position, duration, time;
        public boolean watched;
        public int height;
        public double fraction() { return duration > 0 ? Math.max(0, Math.min(1, position / (double) duration)) : 0; }
    }
    private static final class Data {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Set<String> favorites = new LinkedHashSet<>();
    }

    private static Data data;
    private static boolean dirty;
    private static long lastSave;

    private WatchHistory() {}

    private static synchronized Data data() {
        if (data == null) {
            data = new Data();
            try {
                Path file = file();
                if (Files.isRegularFile(file)) {
                    Data loaded = GSON.fromJson(Files.readString(file), Data.class);
                    if (loaded != null) {
                        if (loaded.entries != null) data.entries = loaded.entries;
                        if (loaded.favorites != null) data.favorites = loaded.favorites;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not read watch history", e);
            }
        }
        return data;
    }
    private static Path file() { return FMLPaths.GAMEDIR.get().resolve("nestworld-video").resolve("history.json"); }
    /** For tests: replace the store without touching the disk. */
    public static synchronized void useMemory() { data = new Data(); dirty = false; lastSave = Long.MAX_VALUE; }

    public static String key(Category category, Video video) {
        String episode = video.getEpisode();
        return category.getName() + "|" + video.getDub() + "|" + (episode.isBlank() ? video.getTitle() : episode);
    }

    public static synchronized Entry get(Category category, Video video) {
        return data().entries.get(key(category, video));
    }

    /** Record progress of a video that is playing. */
    public static synchronized void record(Category category, Video video, long position, long duration, int height, boolean ended) {
        Entry entry = data().entries.computeIfAbsent(key(category, video), k -> new Entry());
        boolean wasWatched = entry.watched;
        entry.category = String.valueOf(category.getName()); entry.dub = video.getDub(); entry.episode = video.getEpisode();
        entry.title = video.getTitle() == null ? "" : video.getTitle();
        entry.position = Math.max(0, position);
        if (duration > 0) entry.duration = duration;
        if (height > 0) entry.height = height;
        entry.time = System.currentTimeMillis();
        if (ended || (entry.duration > 0 && entry.position >= entry.duration * WATCHED_FRACTION)) entry.watched = true;
        if (ended) entry.position = 0;
        dirty = true;
        if (entry.watched != wasWatched || ended) flush(true);
        else flush(false);
    }

    /** Mark an episode (un)watched by hand. */
    public static synchronized void setWatched(Category category, Video video, boolean watched) {
        Entry entry = data().entries.computeIfAbsent(key(category, video), k -> new Entry());
        entry.category = String.valueOf(category.getName()); entry.dub = video.getDub(); entry.episode = video.getEpisode();
        entry.title = video.getTitle() == null ? "" : video.getTitle();
        entry.watched = watched; if (!watched) entry.position = 0;
        entry.time = System.currentTimeMillis();
        dirty = true; flush(true);
    }

    /** The most recently touched entry of a release (any voice), or null. */
    public static synchronized Entry latest(Category category) {
        Entry best = null;
        for (Entry e : data().entries.values())
            if (Objects.equals(e.category, category.getName()) && (best == null || e.time > best.time)) best = e;
        return best;
    }
    public static synchronized boolean touched(Category category) { return latest(category) != null; }

    /** Highest quality (pixel height) this voice of the release was played at, or 0 when it never was. */
    public static synchronized int height(Category category, String dub) {
        int best = 0;
        for (Entry e : data().entries.values())
            if (Objects.equals(e.category, category.getName()) && Objects.equals(e.dub, dub)) best = Math.max(best, e.height);
        return best;
    }

    public static synchronized boolean isFavorite(Category category) { return data().favorites.contains(category.getName()); }
    public static synchronized void toggleFavorite(Category category) {
        if (!data().favorites.remove(category.getName())) data().favorites.add(category.getName());
        dirty = true; flush(true);
    }

    private static void flush(boolean force) {
        long now = System.currentTimeMillis();
        if (lastSave == Long.MAX_VALUE || !dirty || (!force && now - lastSave < 15_000)) return;
        dirty = false; lastSave = now;
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data));
        } catch (Exception e) {
            LOGGER.warn("Could not save watch history", e);
        }
    }
    public static synchronized void saveNow() { if (data != null) { dirty = true; flush(true); } }
}
