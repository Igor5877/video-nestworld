package com.nestworld.video.integration.source;

import com.nestworld.video.integration.VideoSourceResolver;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Tries the references of one title in order and uses the first that resolves, so one dead service does not hide the title. */
public final class SourceChain {
    /** Direct media addresses first (no network), then supported player pages. */
    public static final SourceChain DEFAULT = new SourceChain(List.of(new DirectSource(), new AshdiSource()));

    private final List<SourceProvider> providers;

    public SourceChain(List<SourceProvider> providers) { this.providers = List.copyOf(providers); }

    public CompletableFuture<String> resolve(List<String> references) {
        CompletableFuture<String> result = CompletableFuture.completedFuture("");
        for (String reference : references)
            result = result.thenCompose(found -> found.isEmpty() ? resolveOne(reference) : CompletableFuture.completedFuture(found));
        return result;
    }

    private CompletableFuture<String> resolveOne(String reference) {
        for (SourceProvider provider : providers)
            if (provider.accepts(reference))
                return provider.resolve(reference).exceptionally(error -> "");
        return CompletableFuture.completedFuture("");
    }

    private static final class DirectSource implements SourceProvider {
        @Override public String id() { return "direct"; }
        @Override public boolean accepts(String reference) { return VideoSourceResolver.isDirectMedia(reference); }
        @Override public CompletableFuture<String> resolve(String reference) { return CompletableFuture.completedFuture(reference); }
    }

    private static final class AshdiSource implements SourceProvider {
        @Override public String id() { return "ashdi"; }
        @Override public boolean accepts(String reference) { return VideoSourceResolver.isAshdiPage(reference); }
        @Override public CompletableFuture<String> resolve(String reference) { return VideoSourceResolver.resolvePage(reference); }
    }
}
