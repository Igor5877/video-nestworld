package com.nestworld.video.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
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
    /** Which vanilla render type carries the picture under a shader pack: text | eyes | emissive | beacon. */
    public static volatile String variant = "text";
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
        String kind = variant;
        int key = (glId * 2 + (mipmapped ? 1 : 0)) * 8 + Math.abs(kind.hashCode() % 8);
        Entry entry = CACHE.get(key);
        if (entry != null) return entry.type;
        var manager = Minecraft.getInstance().getTextureManager();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("nestworldvideo", "gl/" + glId + (mipmapped ? "_mip" : ""));
        if (manager.getTexture(location, null) == null) manager.register(location, new GlTexture(glId));
        TextureStateShard texture = new TextureStateShard(location, true, mipmapped);
        RenderType.CompositeState.CompositeStateBuilder state = RenderType.CompositeState.builder()
                .setTextureState(texture).setTransparencyState(NO_TRANSPARENCY).setCullState(NO_CULL).setLightmapState(LIGHTMAP);
        RenderType type;
        switch (kind) {
            case "eyes" -> type = RenderType.create("nestworldvideo_eyes_" + glId, DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS, 256, false, false,
                    state.setShaderState(RENDERTYPE_EYES_SHADER).setOverlayState(OVERLAY).createCompositeState(false));
            case "emissive" -> type = RenderType.create("nestworldvideo_emissive_" + glId, DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS, 256, false, false,
                    state.setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER).setOverlayState(OVERLAY).createCompositeState(false));
            case "beacon" -> type = RenderType.create("nestworldvideo_beacon_" + glId, DefaultVertexFormat.BLOCK,
                    VertexFormat.Mode.QUADS, 256, false, false,
                    state.setShaderState(RENDERTYPE_BEACON_BEAM_SHADER).createCompositeState(false));
            default -> type = RenderType.create("nestworldvideo_screen_" + glId, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                    VertexFormat.Mode.QUADS, 256, false, false,
                    state.setShaderState(RENDERTYPE_TEXT_SHADER).createCompositeState(false));
        }
        CACHE.put(key, new Entry(location, type));
        for (Iterator<Entry> it = CACHE.values().iterator(); CACHE.size() > MAX_ENTRIES && it.hasNext(); ) {
            Entry old = it.next();
            it.remove();
        }
        return type;
    }

    /** Emits one quad in the vertex layout of the current variant. */
    public static void quad(com.mojang.blaze3d.vertex.VertexConsumer consumer, com.mojang.blaze3d.vertex.PoseStack poseStack,
                            float x, float y, float w, float h, float uMax, float vMax, int light) {
        var matrix = poseStack.last().pose();
        var normal = poseStack.last().normal();
        float[][] corners = {{x, y + h, 0, vMax}, {x + w, y + h, uMax, vMax}, {x + w, y, uMax, 0}, {x, y, 0, 0}};
        for (float[] c : corners) {
            var v = consumer.vertex(matrix, c[0], c[1], 0).color(255, 255, 255, 255).uv(c[2], c[3]);
            switch (variant) {
                case "eyes", "emissive" -> v.overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).uv2(light).normal(normal, 0, 0, 1).endVertex();
                case "beacon" -> v.uv2(light).normal(normal, 0, 0, 1).endVertex();
                default -> v.uv2(light).endVertex();
            }
        }
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
