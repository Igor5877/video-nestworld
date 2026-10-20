package com.nestworld.video.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.client.util.Display;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.awt.Dimension;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Enlarging the picture under a shader pack. There the pack's own program draws the screen, so the mod's scaling shader
 * (bicubic / edge / Anime4K) never runs on it; and inside the world pass Oculus blocks colour writes of foreign shaders.
 * So before the world is drawn, every new video frame is enlarged (2x, at most 3840x2160) with that shader into a
 * texture of its own, with mipmaps, and the pack is given that texture instead of the decoded frame.
 */
public final class ShaderUpscaler {
    private static final int MAX_WIDTH = 3840, MAX_HEIGHT = 2160;
    /** Without a frame counter (other decoders) the picture is enlarged at most this often. */
    private static final long UNKNOWN_FRAME_NANOS = 33_000_000L;

    private static final class Target {
        TextureTarget target;
        long serial = Long.MIN_VALUE, lastNanos;
        boolean mipmapped;
    }

    private static final Map<Display, Target> TARGETS = new IdentityHashMap<>();

    private ShaderUpscaler() {}

    /** Used while the picture goes through the shader pack and the scaling shader is on. */
    public static boolean active() {
        // The patched BSL scales the picture itself, per screen pixel; this pass is for other packs.
        return VideoScreenBlockRenderer.useVanillaPipeline() && NestworldConfig.SMOOTH_SCALING.get() && !VideoRenderTypes.patchedPackActive()
                && ClientModEvents.videoScreenShader() != null;
    }

    /** The enlarged picture of {@code display} (it fills the whole texture, uv 0..1), or -1 to draw the frame as it is. */
    public static int textureFor(Display display) {
        Target t = TARGETS.get(display);
        return t == null || t.target == null || t.serial == Long.MIN_VALUE ? -1 : t.target.getColorTextureId();
    }

    public static boolean mipmapped(Display display) {
        Target t = TARGETS.get(display);
        return t != null && t.mipmapped;
    }

    /** Once per frame, before the world is drawn. */
    public static void prepare(Collection<Display> displays) {
        TARGETS.entrySet().removeIf(e -> {
            if (displays.contains(e.getKey()) && active()) return false;
            if (e.getValue().target != null) e.getValue().target.destroyBuffers();
            return true;
        });
        if (!active() || displays.isEmpty()) return;
        boolean drew = false;
        try {
            for (Display display : displays) drew |= enlarge(display);
        } finally {
            if (drew) Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
    }

    private static boolean enlarge(Display display) {
        int source = display.getTextureId();
        Dimension size = display.getDimensions();
        if (source <= 0 || size == null || size.width <= 0 || size.height <= 0) return false;
        Target t = TARGETS.computeIfAbsent(display, d -> new Target());
        if (size.height * 2 > MAX_HEIGHT * 1.5) {   // already 4K-ish: nothing to gain, draw it as it is
            if (t.target != null) { t.target.destroyBuffers(); t.target = null; }
            t.serial = Long.MIN_VALUE;
            return false;
        }
        long serial = display.frameSerial(), now = System.nanoTime();
        if (serial >= 0 ? serial == t.serial : now - t.lastNanos < UNKNOWN_FRAME_NANOS) return false;
        int w = Math.min(MAX_WIDTH, size.width * 2), h = Math.min(MAX_HEIGHT, Math.round(w * (float) size.height / size.width));
        w = Math.round(h * (float) size.width / size.height);
        if (t.target == null || t.target.width != w || t.target.height != h) {
            if (t.target != null) t.target.destroyBuffers();
            t.target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
        }
        var shader = ClientModEvents.videoScreenShader();
        float[] uv = display.uvScale();
        shader.safeGetUniform("Sharpness").set(NestworldConfig.SHARPNESS.get() / 100f);
        shader.safeGetUniform("UvMax").set(uv[0], uv[1]);
        shader.safeGetUniform("Upscaler").set("anime4k".equals(NestworldConfig.UPSCALER.get()) ? 2f : "edge".equals(NestworldConfig.UPSCALER.get()) ? 1f : 0f);

        t.target.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        try {
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, source);
            RenderSystem.bindTexture(source);
            if (!display.managesTextureFilter()) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            }
            // Row 0 of the result is the top of the picture, the same way round as the decoded frame.
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.vertex(-1, -1, 0).uv(0, 0).endVertex();
            builder.vertex(1, -1, 0).uv(uv[0], 0).endVertex();
            builder.vertex(1, 1, 0).uv(uv[0], uv[1]).endVertex();
            builder.vertex(-1, 1, 0).uv(0, uv[1]).endVertex();
            BufferUploader.drawWithShader(builder.end());
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
        }
        t.mipmapped = NestworldConfig.MIPMAPS.get();
        RenderSystem.bindTexture(t.target.getColorTextureId());
        if (t.mipmapped) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        t.serial = serial >= 0 ? serial : now;
        t.lastNanos = now;
        return true;
    }
}
