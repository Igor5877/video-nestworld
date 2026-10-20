package com.nestworld.video.client.glow;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** A surface decal: use the world's depth, but never replace it with the light overlay. */
final class GlowRenderType extends RenderStateShard {
    private static final ResourceLocation WHITE = new ResourceLocation("nestworldvideo", "textures/misc/white.png");

    static final RenderType SURFACE = RenderType.create("nestworldvideo_surface_light",
            DefaultVertexFormat.POSITION_COLOR_TEX, VertexFormat.Mode.QUADS, 4096, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_TEX_SHADER)
                    .setTextureState(new TextureStateShard(WHITE, false, false))
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .createCompositeState(false));

    private GlowRenderType() { super("nestworldvideo_surface_light", () -> {}, () -> {}); }
}
