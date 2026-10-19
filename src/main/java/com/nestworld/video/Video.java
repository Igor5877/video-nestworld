package com.nestworld.video;

public class Video {
    private String title;
    private String description;
    private String url;
    private String streamer_friendly_url;
    private String thumbnail_url;
    private String episode;
    private String dub;
    private String seriesTitle;
    private String provider;
    private Boolean adult;

    public void setEpisodeInfo(String episode, String dub, String seriesTitle, String provider) {
        this.episode = episode; this.dub = dub; this.seriesTitle = seriesTitle; this.provider = provider;
    }
    /** Marked 18+ by hand in videos.json. */
    public boolean isAdultMarked() { return adult != null && adult; }
    public String getEpisode() {
        if (episode != null && !episode.isBlank()) return episode;
        var matcher = java.util.regex.Pattern.compile("(?iu)(?:епізод|серія)\\s+([0-9]+(?:[.,][0-9]+)?)")
                .matcher(description == null ? "" : description);
        return matcher.find() ? matcher.group(1) : "";
    }
    public String getDub() {
        if (dub != null && !dub.isBlank()) return dub;
        if (!getEpisode().isBlank() && title != null && title.contains(" — "))
            return title.substring(title.lastIndexOf(" — ") + 3);
        return "Без позначення озвучки";
    }
    public String getSeriesTitle() {
        if (seriesTitle != null && !seriesTitle.isBlank()) return seriesTitle;
        if (title == null) return "";
        String result = title.replaceFirst("(?iu)^Епізод [^ ]+ — ", "");
        if (!getEpisode().isBlank() && result.contains(" — ")) result = result.substring(0, result.lastIndexOf(" — "));
        return result;
    }
    public String getProvider() { return provider == null ? "" : provider; }
    
    // Конструктор за замовчуванням для Gson
    public Video() {}
    
    // Конструктор для створення об'єктів
    public Video(String title, String description, String url, String streamerFriendlyUrl, String thumbnailUrl) {
        this.title = title;
        this.description = description;
        this.url = url;
        this.streamer_friendly_url = streamerFriendlyUrl;
        this.thumbnail_url = thumbnailUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }

    public String getStreamerFriendlyUrl() {
        return streamer_friendly_url;
    }

    public String getThumbnailUrl() {
        return thumbnail_url;
    }
    
    // Сеттери для оновлення даних
    public void setTitle(String title) {
        this.title = title;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
}
