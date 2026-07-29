package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.gui.texture.rendering.RegisteredGuiTextureRenderer;
import com.lowdragmc.lowdraglib2.gui.texture.rendering.TransformTextureRenderer;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
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

    void drawInternal(GUIContext graphics, float x, float y, float width, float height) {
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

    /**
     * The curve polyline (inline configurator rows and resource previews; the editor dialog draws its own {@code CurveGraph} element, which is why only the dialog kept working).
     * <p>
     * LDLib2 26.1 draws {@link com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture}s through
     * {@code GuiTextureRendererRegistry} (looked up by class, walking superclasses, falling back to a
     * renderer that only handles {@code GuiTexture} lambdas) — the interface no longer has a draw method,
     * so the 1.21-shaped {@code drawInternal} was dead code and this texture rendered nothing.
     */
    @LDLRegisterClient(name = "photon_curve", registry = "ldlib2:gui_texture_renderer")
    public static final class Renderer implements RegisteredGuiTextureRenderer<CurveTexture, Renderer> {
        @Override
        public Class<CurveTexture> type() {
            return CurveTexture.class;
        }

        @Override
        public void draw(CurveTexture texture, GUIContext context, float x, float y, float width, float height) {
            TransformTextureRenderer.draw(texture, context, x, y, width, height, CurveTexture::drawInternal);
        }
    }
}
