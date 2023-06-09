package com.lowdragmc.photon.client.emitter.data.number.curve;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Setter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec2;

import java.util.ArrayList;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/5/30
 * @implNote CurveTexture
 */
public class CurveTexture extends TransformTexture {
    private final ECBCurves curves;

    private int color = ColorPattern.T_RED.color;

    @Setter
    private float width = 0.5f;

    public CurveTexture(ECBCurves curves) {
        this.curves = curves;
    }

    @Override
    public CurveTexture setColor(int color) {
        this.color = color;
        return this;
    }

    @Override
    @Environment(EnvType.CLIENT)
    protected void drawInternal(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, int width, int height) {
        List<Vec2> points = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            float coordX = i * 1f / width;
            points.add(new Vec2(coordX, curves.getCurveY(coordX)));
        }
        points.add(new Vec2(1, curves.getCurveY(1)));
        DrawerHelper.drawLines(graphics, points.stream().map(coord -> new Vec2(x + width * coord.x, y + height * (1 - coord.y))).toList(), color, color, this.width);
    }
}
