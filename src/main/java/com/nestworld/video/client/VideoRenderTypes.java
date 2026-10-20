package com.nestworld.video.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nestworld.video.NestworldConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws a decoder's GL texture through Minecraft's own render pipeline. Shader packs (Iris/Oculus) recognise
 * vanilla render types, so the picture gets the right framebuffer, fog and post-processing instead of being
 * drawn behind their back.
 */
public final class VideoRenderTypes extends RenderStateShard {
    private static final int MAX_ENTRIES = 32;
    private static final Map<Integer, Entry> CACHE = new LinkedHashMap<>();
    private static long lastShaderCheck;
    private static boolean shaderPackActive;

    private record Entry(ResourceLocation location, RenderType type) {}

    private VideoRenderTypes() { super("nestworldvideo_render_types", () -> {}, () -> {}); }

    /** A texture entry that wraps an existing GL id without ever deleting it. */
    private static final class GlTexture extends AbstractTexture {
        GlTexture(int glId) { this.id = glId; }
        @Override public void load(ResourceManager resourceManager) {}
        @Override public void releaseId() {}
        @Override public void close() {}
    }

    /** Full-bright, opaque, bilinear-filtered render type for the given GL texture. */
    public static RenderType forTexture(int glId, boolean mipmapped) {
        int key = glId * 2 + (mipmapped ? 1 : 0);
        Entry entry = CACHE.get(key);
        if (entry != null) return entry.type;
        var manager = Minecraft.getInstance().getTextureManager();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("nestworldvideo", "gl/" + glId + (mipmapped ? "_mip" : ""));
        manager.register(location, new GlTexture(glId));
        RenderType type = RenderType.create("nestworldvideo_screen_" + glId, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                VertexFormat.Mode.QUADS, 256, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_TEXT_SHADER)
                        .setTextureState(new TextureStateShard(location, true, mipmapped))
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .createCompositeState(false));
        CACHE.put(key, new Entry(location, type));
        for (Iterator<Entry> it = CACHE.values().iterator(); CACHE.size() > MAX_ENTRIES && it.hasNext(); ) {
            manager.release(it.next().location);
            it.remove();
        }
        return type;
    }

    /**
     * Opaque render type for the picture that shader packs draw without lighting: it goes through the beacon-beam
     * program, which packs (BSL included) render unlit, so the picture does not pick up sun, sky or block light and
     * keeps its colours day and night. Black stays black (no blending).
     */
    public static RenderType forTextureUnlit(int glId, boolean mipmapped) {
        int key = glId * 2 + (mipmapped ? 1 : 0) + (1 << 30);
        Entry entry = CACHE.get(key);
        if (entry != null) return entry.type;
        var manager = Minecraft.getInstance().getTextureManager();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("nestworldvideo", "gl/" + glId + (mipmapped ? "_mip" : "") + "_unlit");
        manager.register(location, new GlTexture(glId));
        RenderType type = RenderType.create("nestworldvideo_screen_unlit_" + glId, DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS, 256, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_BEACON_BEAM_SHADER)
                        .setTextureState(new TextureStateShard(location, true, mipmapped))
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .createCompositeState(false));
        CACHE.put(key, new Entry(location, type));
        for (Iterator<Entry> it = CACHE.values().iterator(); CACHE.size() > MAX_ENTRIES && it.hasNext(); ) {
            manager.release(it.next().location);
            it.remove();
        }
        return type;
    }

    /** One quad for {@link #forTextureUnlit}: the BLOCK format also carries a normal (facing the viewer, +z). */
    public static void quadUnlit(com.mojang.blaze3d.vertex.VertexConsumer consumer, com.mojang.blaze3d.vertex.PoseStack poseStack,
                                 float x, float y, float w, float h, float uMax, float vMax) {
        var pose = poseStack.last();
        var matrix = pose.pose();
        int r = 255 * NestworldConfig.SHADER_TINT_RED.get() / 100, g = 255 * NestworldConfig.SHADER_TINT_GREEN.get() / 100, b = 255 * NestworldConfig.SHADER_TINT_BLUE.get() / 100;
        int light = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        consumer.vertex(matrix, x, y + h, 0).color(r, g, b, 255).uv(0, vMax).uv2(light).normal(pose.normal(), 0, 0, 1).endVertex();
        consumer.vertex(matrix, x + w, y + h, 0).color(r, g, b, 255).uv(uMax, vMax).uv2(light).normal(pose.normal(), 0, 0, 1).endVertex();
        consumer.vertex(matrix, x + w, y, 0).color(r, g, b, 255).uv(uMax, 0).uv2(light).normal(pose.normal(), 0, 0, 1).endVertex();
        consumer.vertex(matrix, x, y, 0).color(r, g, b, 255).uv(0, 0).uv2(light).normal(pose.normal(), 0, 0, 1).endVertex();
    }

    /**
     * Emits one quad. Shader packs apply their own colour grading to everything, which can tint the picture (BSL
     * turns pure white cream-coloured); the configured per-channel percentages pre-compensate for it.
     */
    public static void quad(com.mojang.blaze3d.vertex.VertexConsumer consumer, com.mojang.blaze3d.vertex.PoseStack poseStack,
                            float x, float y, float w, float h, float uMax, float vMax, int light) {
        var matrix = poseStack.last().pose();
        int r = 255 * NestworldConfig.SHADER_TINT_RED.get() / 100;
        int g = 255 * NestworldConfig.SHADER_TINT_GREEN.get() / 100;
        int b = 255 * NestworldConfig.SHADER_TINT_BLUE.get() / 100;
        consumer.vertex(matrix, x, y + h, 0).color(r, g, b, 255).uv(0, vMax).uv2(light).endVertex();
        consumer.vertex(matrix, x + w, y + h, 0).color(r, g, b, 255).uv(uMax, vMax).uv2(light).endVertex();
        consumer.vertex(matrix, x + w, y, 0).color(r, g, b, 255).uv(uMax, 0).uv2(light).endVertex();
        consumer.vertex(matrix, x, y, 0).color(r, g, b, 255).uv(0, 0).uv2(light).endVertex();
    }

    /** True while an Iris/Oculus shader pack is rendering the world. Checked at most once a second. */
    public static boolean shaderPackActive() {
        long now = System.nanoTime();
        if (now - lastShaderCheck < 1_000_000_000L) return shaderPackActive;
        lastShaderCheck = now;
        shaderPackActive = false;
        for (String name : new String[]{"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"}) {
            try {
                Class<?> api = Class.forName(name);
                Object instance = api.getMethod("getInstance").invoke(null);
                shaderPackActive = (Boolean) api.getMethod("isShaderPackInUse").invoke(instance);
                break;
            } catch (ClassNotFoundException ignored) {
                // not installed: try the next known package
            } catch (ReflectiveOperationException | RuntimeException e) {
                break;
            }
        }
        return shaderPackActive;
    }
}
