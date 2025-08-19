package com.nestworld.video.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.awt.image.BufferedImage;

public class VideoRenderer {

    private DynamicTexture texture;
    // Ми будемо зберігати ResourceLocation, а не RenderType,
    // оскільки RenderType може змінюватися
    private ResourceLocation textureLocation;

    public VideoRenderer() {
    }

    public void updateTexture(BufferedImage image) {
        if (image == null) return;

        try {
            if (this.texture == null || this.texture.getPixels() == null ||
                this.texture.getPixels().getWidth() != image.getWidth() ||
                this.texture.getPixels().getHeight() != image.getHeight()) {

                if (this.texture != null) {
                    // Якщо текстура вже існує, звільняємо її ресурси
                    Minecraft.getInstance().getTextureManager().release(this.textureLocation);
                    this.texture.close();
                }
                
                // Створюємо нову динамічну текстуру
                this.texture = new DynamicTexture(image.getWidth(), image.getHeight(), true);
                // РЕЄСТРУЄМО її в менеджері текстур, щоб отримати ResourceLocation
                this.textureLocation = Minecraft.getInstance().getTextureManager().register("nestworldvideo/dynamic_video", this.texture);
            }

            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    this.texture.getPixels().setPixelRGBA(x, y, image.getRGB(x, y));
                }
            }
            this.texture.upload();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void render(PoseStack poseStack, MultiBufferSource bufferSource, float width, float height) {
        // Якщо текстура ще не готова, нічого не малюємо
        if (this.textureLocation == null) return;

        // ВИПРАВЛЕНО: Отримуємо правильний RenderType, використовуючи наш ResourceLocation
        RenderType renderType = RenderType.entityCutout(this.textureLocation);
        VertexConsumer consumer = bufferSource.getBuffer(renderType);
        Matrix4f matrix = poseStack.last().pose();

        consumer.vertex(matrix, 0, height, 0).uv(0, 1).endVertex();
        consumer.vertex(matrix, width, height, 0).uv(1, 1).endVertex();
        consumer.vertex(matrix, width, 0, 0).uv(1, 0).endVertex();
        consumer.vertex(matrix, 0, 0, 0).uv(0, 0).endVertex();
    }

    public void close() {
        if (this.texture != null) {
            Minecraft.getInstance().getTextureManager().release(this.textureLocation);
            this.texture.close();
            this.texture = null;
            this.textureLocation = null;
        }
    }
}