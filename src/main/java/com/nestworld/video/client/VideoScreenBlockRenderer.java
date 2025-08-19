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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.awt.Dimension;

public class VideoScreenBlockRenderer implements BlockEntityRenderer<VideoScreenBlockEntity> {

    public VideoScreenBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(VideoScreenBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!blockEntity.isPlaying() || blockEntity.getCurrentVideoUrl().isEmpty()) {
            // Ensure video is stopped if the block is not supposed to be playing
            ClientVideoManager.getInstance().stopPlaying(blockEntity.getBlockPos());
            return;
        }

        Display display = ClientVideoManager.getInstance().getDisplay(blockEntity.getBlockPos());
        if (display == null) {
            return; // Display might still be loading
        }

        int textureId = display.getTextureId();
        if (textureId == -1) {
            return; // Video is not ready to be rendered
        }

        poseStack.pushPose();

        // Basic setup from the old renderer
        setupTransformation(poseStack, blockEntity.getBlockState().getValue(VideoScreenBlock.FACING));
        
        // --- Advanced Rendering Logic adapted from the reference project ---
        
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

        // Get video dimensions for aspect ratio
        Dimension videoDim = display.getDimensions();
        float videoAspectRatio = (videoDim.width > 0 && videoDim.height > 0) 
            ? (float) videoDim.width / (float) videoDim.height 
            : 16.0f / 9.0f;

        float screenWidth = 1.0f;
        float screenHeight = 1.0f;
        float screenAspectRatio = screenWidth / screenHeight;

        float renderWidth = screenWidth;
        float renderHeight = screenHeight;
        float xOffset = 0;
        float yOffset = 0;

        if (videoAspectRatio > screenAspectRatio) {
            // Video is wider than the screen (letterboxing)
            renderHeight = screenWidth / videoAspectRatio;
            yOffset = (screenHeight - renderHeight) / 2.0f;
        } else {
            // Video is taller than the screen (pillarboxing)
            renderWidth = screenHeight * videoAspectRatio;
            xOffset = (screenWidth - renderWidth) / 2.0f;
        }

        builder.vertex(matrix, xOffset, yOffset + renderHeight, 0).uv(0, 1).endVertex();
        builder.vertex(matrix, xOffset + renderWidth, yOffset + renderHeight, 0).uv(1, 1).endVertex();
        builder.vertex(matrix, xOffset + renderWidth, yOffset, 0).uv(1, 0).endVertex();
        builder.vertex(matrix, xOffset, yOffset, 0).uv(0, 0).endVertex();
        
        tesselator.end();
        
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();

        poseStack.popPose();
    }

    private void setupTransformation(PoseStack poseStack, Direction facing) {
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(0, 0, 0.5 - 0.001); // Slightly inside the block
        // We render on a 1x1 quad and then scale it
        poseStack.scale(1.0f, -1.0f, 1.0f); // Flip Y for correct UV mapping
        poseStack.translate(-0.5, -0.5, 0);
    }

    @Override
    public boolean shouldRenderOffScreen(VideoScreenBlockEntity blockEntity) {
        return true;
    }
}
