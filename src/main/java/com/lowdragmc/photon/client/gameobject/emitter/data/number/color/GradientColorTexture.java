package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class GradientColorTexture extends TransformTexture {

    public final GradientColor gradientColor;

    public GradientColorTexture(GradientColor gradientColor) {
        this.gradientColor = gradientColor;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected void drawInternal(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, float width, float height, float partialTicks) {
        // render color bar
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        var mat = graphics.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        drawGradient(mat, buffer, x, y, width, height, gradientColor);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    @OnlyIn(Dist.CLIENT)
    public static void drawGradient(Matrix4f mat, BufferBuilder buf,
                                    float x, float y, float width, float height, GradientColor gc) {
        final List<Float> keys = new ArrayList<>();
        keys.add(0f);
        keys.add(1f);
        gc.getAP().forEach(v -> keys.add(v.x));
        gc.getRgbP().forEach(v -> keys.add(v.x));
        var sortedKeys = keys.stream().distinct().sorted().toList();

        final float y2 = y + height;
        for (int i = 0; i < sortedKeys.size() - 1; i++) {
            float t0 = sortedKeys.get(i);
            float t1 = sortedKeys.get(i + 1);
            float x0 = x + t0 * width;
            float x1 = x + t1 * width;

            int c0 = gc.getColor(t0);
            int c1 = gc.getColor(t1);

            float a0 = ((c0 >> 24) & 0xFF) / 255f;
            float r0 = ((c0 >> 16) & 0xFF) / 255f;
            float g0 = ((c0 >>  8) & 0xFF) / 255f;
            float b0 = ( c0        & 0xFF) / 255f;

            float a1 = ((c1 >> 24) & 0xFF) / 255f;
            float r1 = ((c1 >> 16) & 0xFF) / 255f;
            float g1 = ((c1 >>  8) & 0xFF) / 255f;
            float b1 = ( c1        & 0xFF) / 255f;

            buf.addVertex(mat, x1, y , 0).setColor(r1, g1, b1, a1);
            buf.addVertex(mat, x0, y , 0).setColor(r0, g0, b0, a0);
            buf.addVertex(mat, x0, y2, 0).setColor(r0, g0, b0, a0);
            buf.addVertex(mat, x1, y2, 0).setColor(r1, g1, b1, a1);
        }
    }
}
