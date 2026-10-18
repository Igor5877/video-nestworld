package com.nestworld.video;

import java.util.List;

public class Category {
    private String name;
    private List<Video> videos;
    private String displayTitle, poster, season;
    public void setDetails(String title, String poster, String season) {
        this.displayTitle = title; this.poster = poster; this.season = season;
    }
    public String getDisplayTitle() {
        if (displayTitle != null && !displayTitle.isBlank()) return displayTitle;
        if (name != null && name.startsWith("AniUA:") && videos != null && !videos.isEmpty())
            return videos.get(0).getSeriesTitle();
        return name == null ? "Без назви" : name;
    }
    public String getPoster() {
        if (poster != null && !poster.isBlank()) return poster;
        return videos == null || videos.isEmpty() ? "" : videos.get(0).getThumbnailUrl();
    }
    public String getSeason() {
        if (season != null) return season;
        var matcher = java.util.regex.Pattern.compile("(?iu)(\\d+)\\s*сезон|season[ -]*(\\d+)").matcher(getDisplayTitle()+" "+name);
        if (matcher.find()) return "Сезон " + (matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        return "Номер сезону не вказано";
    }
    public Category() {}
    public Category(String name, List<Video> videos) { this.name = name; this.videos = videos; }

    // Геттер для доступу до приватного поля name
    public String getName() {
        return name;
    }

    // Геттер для відео, може знадобитися в майбутньому
    public List<Video> getVideos() {
        return videos;
    }
}
