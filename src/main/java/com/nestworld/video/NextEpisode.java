package com.nestworld.video;

import com.nestworld.video.client.gui.CatalogueOrder;
import java.util.Collection;

/** Which episode follows a given video: same release, same dub, next playable one in episode order. */
public final class NextEpisode {
    private NextEpisode() {}

    /** The video that follows {@code url}, or null when it is the last one (or not in the catalogue). */
    public static Video find(Collection<Category> categories, String url) {
        if (categories == null || url == null || url.isBlank()) return null;
        for (Category category : categories) {
            if (category == null || category.getVideos() == null) continue;
            var episodes = category.getVideos().stream().filter(v -> v != null && v.getUrl() != null).toList();
            Video ended = episodes.stream().filter(v -> url.equals(v.getUrl())).findFirst().orElse(null);
            if (ended == null) continue;
            var sameDub = episodes.stream().filter(v -> !v.getUrl().isBlank() && v.getDub().equals(ended.getDub()))
                    .sorted(CatalogueOrder.EPISODES).toList();
            int index = sameDub.indexOf(ended);
            return index >= 0 && index + 1 < sameDub.size() ? sameDub.get(index + 1) : null;
        }
        return null;
    }
}
