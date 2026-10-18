package com.nestworld.video.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/** One poster per open console. Network/image decoding never run on the render thread. */
final class AnimePoster implements AutoCloseable {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private ResourceLocation texture;
    private String url = "";
    private int generation, width, height;
    private String status = "Немає постера";
    ResourceLocation texture() { return texture; }
    int width() { return width; }
    int height() { return height; }
    String status() { return status; }
    void load(String address) {
        String next = address == null ? "" : address;
        if (next.equals(url)) return;
        close(); url = next;
        if (next.isBlank()) return;
        final int ticket = generation;
        status = "Завантаження…";
        CompletableFuture.supplyAsync(() -> {
            try {
                URI uri = URI.create(next);
                // Only the image hosts used by the catalogue; no arbitrary local URLs.
                String host = uri.getHost();
                if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
                        || !(host.equals("cdn.hikka.io") || host.endsWith(".hikka.io")
                        || host.equals("cdn.myanimelist.net"))) return null;
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
                            return reader.read(0);
                        } finally { reader.dispose(); }
                    }
                }
            } catch (Exception e) { return null; }
        }).thenAccept(bitmap -> Minecraft.getInstance().execute(() -> {
            if (ticket != generation) return;
            if (bitmap == null) { status = "Немає постера"; return; }
            width = bitmap.getWidth(); height = bitmap.getHeight();
            NativeImage image = new NativeImage(width, height, false);
            for (int y=0; y<height; y++) for (int x=0; x<width; x++) {
                int argb = bitmap.getRGB(x,y);
                image.setPixelRGBA(x,y,(argb & 0xff00ff00) | ((argb >> 16) & 255) | ((argb & 255) << 16));
            }
            texture = Minecraft.getInstance().getTextureManager().register("anime_poster", new DynamicTexture(image));
            status = "";
        }));
    }
    @Override public void close() {
        generation++;
        if (texture != null) Minecraft.getInstance().getTextureManager().release(texture);
        texture = null; url = ""; status = "Немає постера";
    }
}
