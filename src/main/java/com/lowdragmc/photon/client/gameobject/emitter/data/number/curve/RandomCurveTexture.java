package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Setter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Vector2f;

import java.util.function.Function;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote RandomCurveTexture
 */
public class RandomCurveTexture extends TransformTexture {
    private final ECBCurves curves0, curves1;

    private int color = ColorPattern.T_GREEN.color;

    @Setter
    private float width = 0.5f;

    public RandomCurveTexture(ECBCurves curves0, ECBCurves curves1) {
        this.curves0 = curves0;
        this.curves1 = curves1;
    }

    @Override
    public RandomCurveTexture setColor(int color) {
        this.color = color;
        return this;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected void drawInternal(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, float width, float height, float partialTicks) {
        // render area
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        var matrix = graphics.pose().last().pose();
        Function<Vector2f, Vector2f> getPointPosition = coord -> new Vector2f(x + width * coord.x, y + height * (1 - coord.y));
        if (width < 2) return;
        for (int i = 0; i < width; i++) {
            float x0 = i * 1f / width;
            float x1 = (i + 1) * 1f / width;

            var p0 = getPointPosition.apply(new Vector2f(x0, curves0.getCurveY(x0)));
            var p1 = getPointPosition.apply(new Vector2f(x1, curves0.getCurveY(x1)));
            var p2 = getPointPosition.apply(new Vector2f(x1, curves1.getCurveY(x1)));
            var p3 = getPointPosition.apply(new Vector2f(x0, curves1.getCurveY(x0)));

            buffer.addVertex(matrix, p0.x, p0.y, 0.0f).setColor(ColorPattern.T_RED.color);
            buffer.addVertex(matrix, p1.x, p1.y, 0.0f).setColor(ColorPattern.T_RED.color);
            buffer.addVertex(matrix, p2.x, p2.y, 0.0f).setColor(ColorPattern.T_RED.color);
            buffer.addVertex(matrix, p3.x, p3.y, 0.0f).setColor(ColorPattern.T_RED.color);

            buffer.addVertex(matrix, p3.x, p3.y, 0.0f).setColor(ColorPattern.T_RED.color);
            buffer.addVertex(matrix, p2.x, p2.y, 0.0f).setColor(ColorPattern.T_RED.color);
            buffer.addVertex(matrix, p1.x, p1.y, 0.0f).setColor(ColorPattern.T_RED.color);
            buffer.addVertex(matrix, p0.x, p0.y, 0.0f).setColor(ColorPattern.T_RED.color);
        }

        BufferUploader.drawWithShader(buffer.buildOrThrow());
        // render lines
        new CurveTexture(curves0).setColor(color).drawInternal(graphics, mouseX, mouseY, x, y, width, height, partialTicks);
        new CurveTexture(curves1).setColor(color).drawInternal(graphics, mouseX, mouseY, x, y, width, height, partialTicks);
    }
}
