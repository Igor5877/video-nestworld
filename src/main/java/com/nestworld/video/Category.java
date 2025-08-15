package com.nestworld.video;

import java.util.List;

public class Category {
    private String name;
    private List<Video> videos;

    // Геттер для доступу до приватного поля name
    public String getName() {
        return name;
    }

    // Геттер для відео, може знадобитися в майбутньому
    public List<Video> getVideos() {
        return videos;
    }
}