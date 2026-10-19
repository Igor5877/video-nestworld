package com.nestworld.video;

import java.util.List;

public class Category {
    private String name;
    private List<Video> videos;
    private String displayTitle, poster, season;
    /** Age rating from the catalogue source (Hikka: g, pg, pg_13, r, r_plus, rx) and an optional manual 18+ mark. */
    private String rating;
    private Boolean adult;
    public void setDetails(String title, String poster, String season) {
        this.displayTitle = title; this.poster = poster; this.season = season;
    }
    public void setRating(String rating) { this.rating = rating; }
    public void setAdult(boolean adult) { this.adult = adult; }
    public String getRating() { return rating == null ? "" : rating; }
    /** True when marked 18+ by hand or when its rating is one of {@code adultRatings}. */
    public boolean isAdult(java.util.List<String> adultRatings) {
        if (adult != null) return adult;
        return rating != null && adultRatings.contains(rating.toLowerCase(java.util.Locale.ROOT));
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
