package com.nestworld.video.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import com.nestworld.video.block.VideoScreenBlock;
import com.nestworld.video.block.VideoScreenBlockEntity;
import com.nestworld.video.client.util.Display;
import net.minecraft.client.renderer.GameRenderer;
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
        // Slave blocks look up the master's Display; master/standalone use their own pos.
        BlockPos masterPos = blockEntity.getMasterPos();
        BlockPos displayPos = (masterPos != null) ? masterPos : blockEntity.getBlockPos();
        Display display = ClientVideoManager.getInstance().getDisplay(displayPos);
        if (display == null) return;

        int textureId = display.getTextureId();
        if (textureId == -1) return;

        poseStack.pushPose();
        setupTransformation(poseStack, blockEntity.getBlockState().getValue(VideoScreenBlock.FACING));

        if (buffer instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, textureId);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // Clamp to edge so adjacent tiles don't bleed into each other at UV boundaries.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

        Matrix4f matrix = poseStack.last().pose();

        int screenCols = blockEntity.getScreenCols();
        int screenRows = blockEntity.getScreenRows();
        int tileCol    = blockEntity.getTileCol();
        int tileRow    = blockEntity.getTileRow();

        if (screenCols == 1 && screenRows == 1) {
            // Single block: letterbox/pillarbox to preserve video aspect ratio.
            Dimension videoDim = display.getDimensions();
            float videoAspect = (videoDim != null && videoDim.width > 0 && videoDim.height > 0)
                ? (float) videoDim.width / videoDim.height
                : 16.0f / 9.0f;

            float renderWidth = 1.0f, renderHeight = 1.0f;
            float xOffset = 0f, yOffset = 0f;
            if (videoAspect > 1.0f) {
                renderHeight = 1.0f / videoAspect;
                yOffset = (1.0f - renderHeight) / 2.0f;
            } else {
                renderWidth = videoAspect;
                xOffset = (1.0f - renderWidth) / 2.0f;
            }

            // Повернули оригінальні UV координати (без дзеркалення)
            builder.vertex(matrix, xOffset,               yOffset + renderHeight, 0).uv(0, 1).endVertex();
            builder.vertex(matrix, xOffset + renderWidth, yOffset + renderHeight, 0).uv(1, 1).endVertex();
            builder.vertex(matrix, xOffset + renderWidth, yOffset,               0).uv(1, 0).endVertex();
            builder.vertex(matrix, xOffset,               yOffset,               0).uv(0, 0).endVertex();
        } else {
            // Multi-block: each tile renders its UV slice (stretch to fill).
            
            // Залишаємо виправлення порядку колонок, оскільки блоки рахуються справа наліво
            int actualTileCol = (screenCols - 1) - tileCol;

            float uMin = (float) actualTileCol / screenCols;
            float uMax = (float) (actualTileCol + 1) / screenCols;
            float vTop = (float) (screenRows - 1 - tileRow) / screenRows;
            float vBot = (float) (screenRows - tileRow) / screenRows;

            // Повернули оригінальні UV координати, щоб прибрати ефект дзеркала всередині самого блоку
            builder.vertex(matrix, 0, 1, 0).uv(uMin, vBot).endVertex();
            builder.vertex(matrix, 1, 1, 0).uv(uMax, vBot).endVertex();
            builder.vertex(matrix, 1, 0, 0).uv(uMax, vTop).endVertex();
            builder.vertex(matrix, 0, 0, 0).uv(uMin, vTop).endVertex();
        }

        tesselator.end();

        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();

        poseStack.popPose();
    }

    private void setupTransformation(PoseStack poseStack, Direction facing) {
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(0, 0, 0.5 + 0.001);
        poseStack.scale(1.0f, -1.0f, 1.0f);
        poseStack.translate(-0.5, -0.5, 0);
    }

    @Override
    public boolean shouldRenderOffScreen(VideoScreenBlockEntity blockEntity) {
        return true;
    }
}