package com.nestworld.video.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Posters for the result cards. Downloads and decoding happen off the render thread, textures are created on it, and the
 * least recently used ones are released so a long browsing session does not fill video memory. Render thread only.
 */
public final class PosterCache {
    public record Poster(ResourceLocation location, int width, int height) {}

    private static final int MAX_POSTERS = 48, THUMB_HEIGHT = 220;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    /** null value = requested and not available (yet or failed); a Poster = ready. Access order gives LRU eviction. */
    private static final Map<String, Poster> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static final java.util.Set<String> LOADING = new java.util.HashSet<>();

    private PosterCache() {}

    /** The poster if it is ready; otherwise starts loading it and returns null. */
    public static Poster get(String url) {
        if (url == null || url.isBlank()) return null;
        Poster poster = CACHE.get(url);
        if (poster != null) return poster;
        if (!CACHE.containsKey(url) && LOADING.add(url)) {
            CompletableFuture.supplyAsync(() -> download(url)).thenAccept(image ->
                    Minecraft.getInstance().execute(() -> { LOADING.remove(url); put(url, image); }));
        }
        return null;
    }

    /** Registers an already decoded image for the address (also used to preload posters in tests). */
    public static void put(String url, BufferedImage bitmap) {
        if (bitmap == null) { CACHE.put(url, null); return; }
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int argb = bitmap.getRGB(x, y);
            image.setPixelRGBA(x, y, (argb & 0xff00ff00) | ((argb >> 16) & 255) | ((argb & 255) << 16));
        }
        var manager = Minecraft.getInstance().getTextureManager();
        ResourceLocation location = manager.register("poster_card", new DynamicTexture(image));
        CACHE.put(url, new Poster(location, width, height));
        var it = CACHE.entrySet().iterator();
        while (CACHE.size() > MAX_POSTERS && it.hasNext()) {
            var eldest = it.next();
            if (eldest.getValue() != null) manager.release(eldest.getValue().location());
            it.remove();
        }
    }

    private static BufferedImage download(String address) {
        try {
            URI uri = URI.create(address);
            // Only the image hosts used by the catalogue; no arbitrary local URLs.
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || !(host.equals("cdn.hikka.io") || host.endsWith(".hikka.io") || host.equals("cdn.myanimelist.net"))) return null;
            var response = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) return null;
                byte[] bytes = body.readNBytes(4_000_001);
                if (bytes.length > 4_000_000) return null;
                try (var input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) return null;
                    var reader = readers.next();
                    try {
                        reader.setInput(input);
                        int w = reader.getWidth(0), h = reader.getHeight(0);
                        if (w <= 0 || h <= 0 || w > 2048 || h > 2048) return null;
                        return thumbnail(reader.read(0));
                    } finally { reader.dispose(); }
                }
            }
        } catch (Exception e) { return null; }
    }

    /** Cards show small posters; keep their textures small too. */
    private static BufferedImage thumbnail(BufferedImage source) {
        if (source.getHeight() <= THUMB_HEIGHT) return source;
        int h = THUMB_HEIGHT, w = Math.max(1, Math.round(source.getWidth() * (h / (float) source.getHeight())));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
