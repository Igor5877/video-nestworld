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
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.awt.Dimension;

public class VideoScreenBlockRenderer implements BlockEntityRenderer<VideoScreenBlockEntity> {

    public VideoScreenBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(VideoScreenBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Display display = ClientVideoManager.getInstance().getDisplay(blockEntity.getBlockPos());
        if (display == null) return;

        int textureId = display.getTextureId();
        if (textureId == -1) return;

        poseStack.pushPose();
        setupTransformation(poseStack, blockEntity.getBlockState().getValue(VideoScreenBlock.FACING));

        // Flush batched rendering before issuing raw GL draw calls
        if (buffer instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.bindTexture(textureId);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

        Matrix4f matrix = poseStack.last().pose();

        // Letterbox/pillarbox to preserve aspect ratio
        Dimension videoDim = display.getDimensions();
        float videoAspect = (videoDim.width > 0 && videoDim.height > 0)
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

        builder.vertex(matrix, xOffset,               yOffset + renderHeight, 0).uv(0, 1).endVertex();
        builder.vertex(matrix, xOffset + renderWidth, yOffset + renderHeight, 0).uv(1, 1).endVertex();
        builder.vertex(matrix, xOffset + renderWidth, yOffset,               0).uv(1, 0).endVertex();
        builder.vertex(matrix, xOffset,               yOffset,               0).uv(0, 0).endVertex();

        tesselator.end();

        RenderSystem.disableBlend();
        // Restore depth test — do NOT call disableDepthTest() here, it would break subsequent rendering
        RenderSystem.enableDepthTest();

        poseStack.popPose();
    }

    private void setupTransformation(PoseStack poseStack, Direction facing) {
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(0, 0, 0.5 + 0.001); // Slightly in front of the block face to avoid depth-buffer occlusion
        // We render on a 1x1 quad and then scale it
        poseStack.scale(1.0f, -1.0f, 1.0f); // Flip Y for correct UV mapping
        poseStack.translate(-0.5, -0.5, 0);
    }

    @Override
    public boolean shouldRenderOffScreen(VideoScreenBlockEntity blockEntity) {
        return true;
    }
}
