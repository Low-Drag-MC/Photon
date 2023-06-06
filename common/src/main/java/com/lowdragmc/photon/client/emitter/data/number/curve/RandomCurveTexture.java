package com.lowdragmc.photon.client.emitter.data.number.curve;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec2;

import java.util.ArrayList;
import java.util.List;
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
    @Environment(EnvType.CLIENT)
    protected void drawInternal(PoseStack poseStack, int mouseX, int mouseY, float x, float y, int width, int height) {
        // render area
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        var matrix = poseStack.last().pose();
        Function<Vec2, Vec2> getPointPosition = coord -> new Vec2(x + width * coord.x, y + height * (1 - coord.y));
        for (int i = 0; i < width; i++) {
            float x0 = i * 1f / width;
            float x1 = (i + 1) * 1f / width;

            var p0 = getPointPosition.apply(new Vec2(x0, curves0.getCurveY(x0)));
            var p1 = getPointPosition.apply(new Vec2(x1, curves0.getCurveY(x1)));
            var p2 = getPointPosition.apply(new Vec2(x1, curves1.getCurveY(x1)));
            var p3 = getPointPosition.apply(new Vec2(x0, curves1.getCurveY(x0)));

            bufferBuilder.vertex(matrix, p0.x, p0.y, 0.0f).color(ColorPattern.T_RED.color).endVertex();
            bufferBuilder.vertex(matrix, p1.x, p1.y, 0.0f).color(ColorPattern.T_RED.color).endVertex();
            bufferBuilder.vertex(matrix, p2.x, p2.y, 0.0f).color(ColorPattern.T_RED.color).endVertex();
            bufferBuilder.vertex(matrix, p3.x, p3.y, 0.0f).color(ColorPattern.T_RED.color).endVertex();

            bufferBuilder.vertex(matrix, p3.x, p3.y, 0.0f).color(ColorPattern.T_RED.color).endVertex();
            bufferBuilder.vertex(matrix, p2.x, p2.y, 0.0f).color(ColorPattern.T_RED.color).endVertex();
            bufferBuilder.vertex(matrix, p1.x, p1.y, 0.0f).color(ColorPattern.T_RED.color).endVertex();
            bufferBuilder.vertex(matrix, p0.x, p0.y, 0.0f).color(ColorPattern.T_RED.color).endVertex();
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.enableTexture();
        // render lines
        renderLines(poseStack, curves0, x, y, width, height);
        renderLines(poseStack, curves1, x, y, width, height);
    }

    @Environment(EnvType.CLIENT)
    private void renderLines(PoseStack poseStack, ECBCurves curves, float x, float y, int width, int height) {
        List<Vec2> points = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            float coordX = i * 1f / width;
            points.add(new Vec2(coordX, curves.getCurveY(coordX)));
        }
        points.add(new Vec2(1, curves.getCurveY(1)));
        DrawerHelper.drawLines(poseStack, points.stream().map(coord -> new Vec2(x + width * coord.x, y + height * (1 - coord.y))).toList(), color, color, this.width);
    }

}
