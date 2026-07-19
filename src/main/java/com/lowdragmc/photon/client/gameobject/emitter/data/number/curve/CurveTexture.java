package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.gui.util.DrawerHelperClient;
import lombok.Setter;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import org.joml.Vector2f;

import java.util.ArrayList;

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

    // TODO(M4): register a GuiTextureRenderer so this draws through the 26.1 texture registry
    protected void drawInternal(GUIContext graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTicks) {
        var points = new ArrayList<Vector2f>();
        for (int i = 0; i < width; i++) {
            float coordX = i * 1f / width;
            points.add(new Vector2f(coordX, curves.getCurveY(coordX)));
        }
        if (points.size() < 2) return;
        points.add(new Vector2f(1, curves.getCurveY(1)));
        DrawerHelperClient.drawLines(
                graphics,
                points.stream().map(coord -> new Vector2f(x + width * coord.x, y + height * (1 - coord.y))).toList(),
                color,
                color,
                this.width);
    }
}
