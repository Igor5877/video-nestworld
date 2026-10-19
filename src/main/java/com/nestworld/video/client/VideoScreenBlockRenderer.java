package com.nestworld.video.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.block.VideoScreenBlock;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.client.util.Display;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.Dimension;

public class VideoScreenBlockRenderer implements BlockEntityRenderer<VideoScreenBlockEntity> {

    public VideoScreenBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(VideoScreenBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!blockEntity.isMaster()) return;
        ClientVideoManager.getInstance().observe(blockEntity);
        Display display = ClientVideoManager.getInstance().getDisplay(blockEntity.getBlockPos());
        poseStack.pushPose();
        setupTransformation(poseStack, blockEntity.getBlockState().getValue(VideoScreenBlock.FACING));

        if (buffer instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }

        // Keep the screen visibly alive while WaterMedia opens the stream. This
        // also covers the short interval before ClientVideoManager has created a
        // Display object on the render thread.
        if (display == null) {
            if (blockEntity.isAdult() && !ClientData.isAdultAllowed() && !blockEntity.isStopped() && !blockEntity.getCurrentVideoUrl().isBlank())
                drawAdultStub(poseStack, buffer, blockEntity.getScreenCols(), blockEntity.getScreenRows());
            else if (blockEntity.isPlaying() && !blockEntity.getCurrentVideoUrl().isBlank())
                drawLoading(poseStack, blockEntity.getScreenCols(), blockEntity.getScreenRows(), false);
            else
                drawBackground(poseStack, blockEntity.getScreenCols(), blockEntity.getScreenRows());
            poseStack.popPose();
            return;
        }
        int textureId = display.getTextureId();
        if (textureId <= 0) {
            drawLoading(poseStack, blockEntity.getScreenCols(), blockEntity.getScreenRows(), display.isBroken());
            poseStack.popPose();
            return;
        }

        if (useVanillaPipeline()) {
            drawThroughVanillaPipeline(poseStack, buffer, display, textureId, blockEntity.getScreenCols(), blockEntity.getScreenRows());
            poseStack.popPose();
            return;
        }

        RenderSystem.enableDepthTest();
        // Video frames are opaque. Alpha blending can let the previous world
        // framebuffer bleed through when a decoder texture has a non-opaque alpha
        // channel, which looks like a faint trail behind moving objects.
        RenderSystem.disableBlend();
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(0.0F, 0.0F);

        // Cover the block's static texture first. This keeps aspect-ratio letterbox
        // bars stable at distance instead of letting the block face z-fight through.
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = poseStack.last().pose();
        drawColorQuad(matrix, 0, 1 - blockEntity.getScreenRows(),
                blockEntity.getScreenCols(), 1, 0.008f, 0.008f, 0.008f, 1f);

        RenderSystem.polygonOffset(-1.0F, -4.0F);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        var smooth = NestworldConfig.SMOOTH_SCALING.get() ? ClientModEvents.videoScreenShader() : null;
        if (smooth != null) {
            smooth.safeGetUniform("Sharpness").set(NestworldConfig.SHARPNESS.get() / 100f);
            float[] visible = display.uvScale();
            smooth.safeGetUniform("UvMax").set(visible[0], visible[1]);
            smooth.safeGetUniform("Upscaler").set("anime4k".equals(NestworldConfig.UPSCALER.get()) ? 2f : "edge".equals(NestworldConfig.UPSCALER.get()) ? 1f : 0f);
            RenderSystem.setShader(() -> smooth);
        }
        else RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, textureId);
        RenderSystem.bindTexture(textureId);

        if (!display.managesTextureFilter()) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        }
        // Clamp to edge so adjacent tiles don't bleed into each other at UV boundaries.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

        float cols = blockEntity.getScreenCols(), rows = blockEntity.getScreenRows();
        Dimension videoDim = display.getDimensions();
        float aspect = videoDim != null && videoDim.width > 0 && videoDim.height > 0
                ? (float) videoDim.width / videoDim.height : 16f / 9f;
        float w = cols, h = rows;
        if (cols / rows > aspect) w = rows * aspect;
        else h = cols / aspect;
        float x = (cols - w) / 2f, y = 1 - rows + (rows - h) / 2f;
        float[] scale = display.uvScale();
        builder.vertex(matrix, x, y + h, 0).uv(0, scale[1]).endVertex();
        builder.vertex(matrix, x + w, y + h, 0).uv(scale[0], scale[1]).endVertex();
        builder.vertex(matrix, x + w, y, 0).uv(scale[0], 0).endVertex();
        builder.vertex(matrix, x, y, 0).uv(0, 0).endVertex();

        tesselator.end();

        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();

        poseStack.popPose();
    }

    private static boolean useVanillaPipeline() {
        return switch (NestworldConfig.RENDER_MODE.get()) {
            case "vanilla_pipeline" -> true;
            case "direct" -> false;
            default -> VideoRenderTypes.shaderPackActive();
        };
    }

    /** Same picture as the direct path, but submitted as a regular render type so shader packs can process it. */
    private void drawThroughVanillaPipeline(PoseStack poseStack, MultiBufferSource buffer, Display display, int textureId,
                                            float cols, float rows) {
        Dimension videoDim = display.getDimensions();
        float aspect = videoDim != null && videoDim.width > 0 && videoDim.height > 0
                ? (float) videoDim.width / videoDim.height : 16f / 9f;
        float w = cols, h = rows;
        if (cols / rows > aspect) w = rows * aspect;
        else h = cols / aspect;
        float x = (cols - w) / 2f, y = 1 - rows + (rows - h) / 2f;
        VertexConsumer consumer = buffer.getBuffer(VideoRenderTypes.forTexture(textureId, display.hasMipmaps()));
        Matrix4f matrix = poseStack.last().pose();
        int light = LightTexture.FULL_BRIGHT;
        float[] scale = display.uvScale();
        VideoRenderTypes.quad(consumer, poseStack, x, y, w, h, scale[0], scale[1], LightTexture.FULL_BRIGHT);
    }

    /** What a viewer without 18+ access sees instead of the video: a dark panel with a gold "18+" ring and a short note. */
    private void drawAdultStub(PoseStack poseStack, MultiBufferSource buffer, int cols, int rows) {
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0F, -4.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = poseStack.last().pose();
        drawColorQuad(matrix, 0, 1 - rows, cols, 1, 0.045f, 0.05f, 0.065f, 1f);
        float unit = Math.min(cols, rows);
        float cx = cols * 0.5f, cy = 1f - rows * 0.5f - unit * 0.06f;
        float radius = unit * 0.24f, thickness = unit * 0.022f;
        // The ring is one strip of touching quads (overlapping ones would z-fight and flicker).
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int segments = 64;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0 / segments, a1 = (i + 1) * Math.PI * 2.0 / segments;
            float inner = radius - thickness, outer = radius + thickness;
            // Same winding as drawColorQuad, otherwise the ring is culled.
            builder.vertex(matrix, cx + (float) Math.cos(a1) * inner, cy + (float) Math.sin(a1) * inner, 0).color(0.906f, 0.745f, 0.439f, 1f).endVertex();
            builder.vertex(matrix, cx + (float) Math.cos(a1) * outer, cy + (float) Math.sin(a1) * outer, 0).color(0.906f, 0.745f, 0.439f, 1f).endVertex();
            builder.vertex(matrix, cx + (float) Math.cos(a0) * outer, cy + (float) Math.sin(a0) * outer, 0).color(0.906f, 0.745f, 0.439f, 1f).endVertex();
            builder.vertex(matrix, cx + (float) Math.cos(a0) * inner, cy + (float) Math.sin(a0) * inner, 0).color(0.906f, 0.745f, 0.439f, 1f).endVertex();
        }
        tesselator.end();
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.disableBlend();
        var font = net.minecraft.client.Minecraft.getInstance().font;
        float maxWidth = cols * 0.9f;
        centeredText(poseStack, buffer, font, "18+", cx, cy, unit * 0.032f, 0xFFE7BE70, maxWidth);
        centeredText(poseStack, buffer, font, "Контент 18+", cx, cy + radius + unit * 0.11f, unit * 0.0135f, 0xFFE8EDF2, maxWidth);
        centeredText(poseStack, buffer, font, "У вас немає доступу до цього відео", cx, cy + radius + unit * 0.21f, unit * 0.0085f, 0xFFAAB8C7, maxWidth);
    }

    /** Draws {@code text} centred on (x, y) in screen-local units; scale is the size of one font pixel. */
    private void centeredText(PoseStack poseStack, MultiBufferSource buffer, net.minecraft.client.gui.Font font, String text,
                              float x, float y, float scale, int color, float maxWidth) {
        scale = Math.min(scale, maxWidth / Math.max(1, font.width(text)));
        poseStack.pushPose();
        poseStack.translate(x - font.width(text) * scale / 2f, y - 4f * scale, 0.006f);   // the screen surface faces +z: put the text in front of it
        poseStack.scale(scale, scale, 1f);
        font.drawInBatch(text, 0f, 0f, color, false, poseStack.last().pose(), buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    private void drawBackground(PoseStack poseStack, int cols, int rows) {
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0F, -4.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        drawColorQuad(poseStack.last().pose(), 0, 1 - rows, cols, 1,
                0.008f, 0.008f, 0.008f, 1f);
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.disableBlend();
    }

    private void drawLoading(PoseStack poseStack, int cols, int rows, boolean broken) {
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0F, -4.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = poseStack.last().pose();
        float left = 0, right = cols, top = 1 - rows, bottom = 1;
        drawColorQuad(matrix, left, top, right, bottom, broken ? 0.055f : 0.025f,
                broken ? 0.018f : 0.035f, broken ? 0.018f : 0.055f, 1f);

        // Twelve dots chase one another around the centre. The radius scales
        // with the wall, but is capped so a 100x100 screen stays readable.
        float scale = Math.min(cols, rows);
        float radius = Math.max(0.16f, Math.min(3.5f, scale * 0.22f));
        float dot = Math.max(0.035f, Math.min(0.34f, scale * 0.035f));
        float phase = (System.nanoTime() / 1_000_000_000f) * 4.2f;
        float cx = cols * 0.5f, cy = 1f - rows * 0.5f;
        if (broken) {
            // A static red cross means startup failed or exceeded its timeout;
            // keep it visually distinct from the animated loading dots.
            float halfLength = Math.max(0.13f, Math.min(0.8f, scale * 0.22f));
            float halfThickness = Math.max(0.035f, Math.min(0.18f, scale * 0.065f));
            drawRotatedMark(matrix, cx, cy, halfLength, halfThickness, (float)Math.PI / 4f);
            drawRotatedMark(matrix, cx, cy, halfLength, halfThickness, -(float)Math.PI / 4f);
        } else {
            int count = 12;
            for (int i = 0; i < count; i++) {
                double angle = phase + i * Math.PI * 2.0 / count;
                float x = cx + (float)Math.cos(angle) * radius;
                float y = cy + (float)Math.sin(angle) * radius;
                float brightness = 0.28f + 0.72f * ((i + count) % count) / (float)count;
                drawColorQuad(matrix, x - dot, y - dot, x + dot, y + dot,
                        brightness * 0.92f, brightness * 0.68f, brightness * 0.16f, 1f);
            }
        }
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.disableBlend();
    }

    private void drawColorQuad(Matrix4f matrix, float left, float top, float right, float bottom,
                                float red, float green, float blue, float alpha) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        builder.vertex(matrix, left, bottom, 0).color(red, green, blue, alpha).endVertex();
        builder.vertex(matrix, right, bottom, 0).color(red, green, blue, alpha).endVertex();
        builder.vertex(matrix, right, top, 0).color(red, green, blue, alpha).endVertex();
        builder.vertex(matrix, left, top, 0).color(red, green, blue, alpha).endVertex();
        tesselator.end();
    }

    private void drawRotatedMark(Matrix4f matrix, float cx, float cy, float halfLength,
                                 float halfThickness, float angle) {
        float cos = (float)Math.cos(angle), sin = (float)Math.sin(angle);
        float[][] corners = {{-halfLength, -halfThickness}, {halfLength, -halfThickness},
                {halfLength, halfThickness}, {-halfLength, halfThickness}};
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 4; i++) {
            float x = corners[i][0], y = corners[i][1];
            float rx = cx + x * cos - y * sin;
            float ry = cy + x * sin + y * cos;
            builder.vertex(matrix, rx, ry, 0).color(0.95f, 0.08f, 0.07f, 1f).endVertex();
        }
        tesselator.end();
    }

    private void setupTransformation(PoseStack poseStack, Direction facing) {
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        // Keep the video surface clearly in front of the block model. The old
        // 0.001 offset was enough up close but z-fought at long distances.
        poseStack.translate(0, 0, 0.5 + 0.01);
        poseStack.scale(1.0f, -1.0f, 1.0f);
        poseStack.translate(-0.5, -0.5, 0);
    }

    @Override public int getViewDistance() { return 256; }

    @Override
    public boolean shouldRenderOffScreen(VideoScreenBlockEntity blockEntity) {
        return blockEntity.isMaster();
    }
}
