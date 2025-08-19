package com.nestworld.video;

public class Video {
    private String title;
    private String description;
    private String url;
    private String streamer_friendly_url;
    private String thumbnail_url;
    
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