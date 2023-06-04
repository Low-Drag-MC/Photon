package com.lowdragmc.photon.client.data.number.color;

import com.lowdragmc.lowdraglib.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib.utils.GradientColor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Matrix4f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.GameRenderer;

import java.util.function.Function;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote RandomGradientColorTexture
 */
public class RandomGradientColorTexture extends TransformTexture {

    public final GradientColor gradientColor0;
    public final GradientColor gradientColor1;

    public RandomGradientColorTexture(GradientColor gradientColor0, GradientColor gradientColor1) {
        this.gradientColor0 = gradientColor0;
        this.gradientColor1 = gradientColor1;
    }

    @Override
    @Environment(EnvType.CLIENT)
    protected void drawInternal(PoseStack stack, int mouseX, int mouseY, float posx, float posy, int width, int height) {
        // render color bar
        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Matrix4f mat = stack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        Function<Float, Float> getXPosition = coordX -> posx + width * coordX;
        var yh = height / 2f + posy;
        drawGradient(posy, width, mat, buffer, getXPosition, yh, gradientColor0);

        posy += height / 2f;
        yh = height / 2f + posy;
        drawGradient(posy, width, mat, buffer, getXPosition, yh, gradientColor1);

        tesselator.end();
        RenderSystem.enableTexture();
    }

    static void drawGradient(float posy, int width, Matrix4f mat, BufferBuilder buffer, Function<Float, Float> getXPosition, float yh, GradientColor gradientColor) {
        for (int i = 0; i < width; i++) {
            var x = getXPosition.apply(i * 1f / width);
            var xw = getXPosition.apply((i + 1f) / width);
            int startColor = gradientColor.getColor(i * 1f / width);
            int endColor = gradientColor.getColor((i + 1f) / width);
            float startAlpha = (float)(startColor >> 24 & 255) / 255.0F;
            float startRed   = (float)(startColor >> 16 & 255) / 255.0F;
            float startGreen = (float)(startColor >>  8 & 255) / 255.0F;
            float startBlue  = (float)(startColor       & 255) / 255.0F;
            float endAlpha   = (float)(endColor   >> 24 & 255) / 255.0F;
            float endRed     = (float)(endColor   >> 16 & 255) / 255.0F;
            float endGreen   = (float)(endColor   >>  8 & 255) / 255.0F;
            float endBlue    = (float)(endColor         & 255) / 255.0F;

            buffer.vertex(mat, xw, posy, 0).color(endRed, endGreen, endBlue, endAlpha).endVertex();
            buffer.vertex(mat, x, posy, 0).color(startRed, startGreen, startBlue, startAlpha).endVertex();
            buffer.vertex(mat, x, yh, 0).color(startRed, startGreen, startBlue, startAlpha).endVertex();
            buffer.vertex(mat, xw, yh, 0).color(endRed, endGreen, endBlue, endAlpha).endVertex();
        }
    }
}
