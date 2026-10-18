package com.nestworld.video;

import java.util.List;

public class VideoConfig {
    private List<Category> categories;
    public VideoConfig() {}
    public VideoConfig(List<Category> categories) { this.categories = categories; }
    public List<Category> getCategories() { return categories; }
}
