package com.nestworld.video.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nestworld.video.block.VideoScreenBlock;
import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

public class VideoScreenBlockRenderer implements BlockEntityRenderer<VideoScreenBlockEntity> {
    
    private final VideoRenderer videoRenderer;
    
    public VideoScreenBlockRenderer(BlockEntityRendererProvider.Context context) {
        this.videoRenderer = new VideoRenderer();
    }
    
    @Override
    public void render(VideoScreenBlockEntity blockEntity, float partialTick, PoseStack poseStack, 
                      MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        
        // Якщо немає відео для показу, не рендеримо
        if (blockEntity.getCurrentVideoUrl().isEmpty() || !blockEntity.isPlaying()) {
            return;
        }
        
        BlockState blockState = blockEntity.getBlockState();
        Direction facing = blockState.getValue(VideoScreenBlock.FACING);
        
        poseStack.pushPose();
        
        // Переміщаємось до центру блоку
        poseStack.translate(0.5f, 0.5f, 0.5f);
        
        // Повертаємо відповідно до напрямку блоку
        switch (facing) {
            case NORTH:
                poseStack.mulPose(Axis.YP.rotationDegrees(180));
                break;
            case SOUTH:
                // Без ротації (за замовчуванням дивиться на південь)
                break;
            case WEST:
                poseStack.mulPose(Axis.YP.rotationDegrees(90));
                break;
            case EAST:
                poseStack.mulPose(Axis.YP.rotationDegrees(-90));
                break;
        }
        
        // Зміщуємо трохи вперед від поверхні блоку
        poseStack.translate(0, 0, 0.501f);
        
        // Масштабуємо до розміру екрана (0.9 x 0.9 блоку)
        poseStack.scale(0.9f, 0.9f, 1.0f);
        
        // Центруємо
        poseStack.translate(-0.5f, -0.5f, 0);
        
        // TODO: Тут потрібно буде підключити VLCJ для отримання кадрів відео
        // і передавати їх в videoRenderer.updateTexture()
        // Поки що просто рендеримо базовий квадрат
        
        videoRenderer.render(poseStack, bufferSource, 1.0f, 1.0f);
        
        poseStack.popPose();
    }
    
    @Override
    public boolean shouldRenderOffScreen(VideoScreenBlockEntity blockEntity) {
        return false;
    }
}