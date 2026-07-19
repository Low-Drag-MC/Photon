package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import net.minecraft.client.renderer.RenderPipelines;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

import java.util.ArrayList;
import java.util.List;

public class GradientColorTexture extends TransformTexture {

    public final GradientColor gradientColor;

    public GradientColorTexture(GradientColor gradientColor) {
        this.gradientColor = gradientColor;
    }

    // TODO(M4): register a GuiTextureRenderer so this draws through the 26.1 texture registry
    protected void drawInternal(GUIContext graphics, float mouseX, float mouseY, float x, float y, float width, float height, float partialTicks) {
        drawGradient(graphics, x, y, width, height, gradientColor);
    }

    /** Multi-stop gradient bar as per-corner-colored {@code context.fill} segments (26.1 GUI idiom —
     *  corner order TL, BL, BR, TR, see {@code DrawerHelperClient.drawGradientRect}). */
    public static void drawGradient(GUIContext graphics,
                                    float x, float y, float width, float height, GradientColor gc) {
        final List<Float> keys = new ArrayList<>();
        keys.add(0f);
        keys.add(1f);
        gc.getAP().forEach(v -> keys.add(v.x()));
        gc.getRgbP().forEach(v -> keys.add(v.x()));
        var sortedKeys = keys.stream().distinct().sorted().toList();

        final float y2 = y + height;
        for (int i = 0; i < sortedKeys.size() - 1; i++) {
            float t0 = sortedKeys.get(i);
            float t1 = sortedKeys.get(i + 1);
            float x0 = x + t0 * width;
            float x1 = x + t1 * width;

            int c0 = gc.getColor(t0);
            int c1 = gc.getColor(t1);

            graphics.fill(RenderPipelines.GUI, x0, y, x1, y2, c0, c0, c1, c1);
        }
    }
}
