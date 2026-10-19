package com.nestworld.video.integration.source;

import java.util.concurrent.CompletableFuture;

/**
 * One way of turning a reference (a player page or a media address) into a playable m3u8/mp4 URL.
 * Providers only accept references they explicitly know; arbitrary pages are never passed to the player.
 */
public interface SourceProvider {
    /** Short stable name, used in logs. */
    String id();

    /** True when this provider understands the reference. */
    boolean accepts(String reference);

    /** The media URL, or "" when it cannot be resolved right now. Never throws. */
    CompletableFuture<String> resolve(String reference);
}
