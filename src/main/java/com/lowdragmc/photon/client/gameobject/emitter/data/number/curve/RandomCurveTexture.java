package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import net.minecraft.client.renderer.RenderPipelines;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.texture.TransformTexture;
import com.lowdragmc.lowdraglib2.gui.texture.rendering.RegisteredGuiTextureRenderer;
import com.lowdragmc.lowdraglib2.gui.texture.rendering.TransformTextureRenderer;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.mojang.blaze3d.vertex.*;
import lombok.Setter;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
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

    void drawInternal(GUIContext graphics, float x, float y, float width, float height) {
        // render area
        // 26.1: the between-curves band as fillTriangle pairs (GUI render states, no immediate buffer)
        Function<Vector2f, Vector2f> getPointPosition = coord -> new Vector2f(x + width * coord.x, y + height * (1 - coord.y));
        if (width < 2) return;
        for (int i = 0; i < width; i++) {
            float x0 = i * 1f / width;
            float x1 = (i + 1) * 1f / width;

            var p0 = getPointPosition.apply(new Vector2f(x0, curves0.getCurveY(x0)));
            var p1 = getPointPosition.apply(new Vector2f(x1, curves0.getCurveY(x1)));
            var p2 = getPointPosition.apply(new Vector2f(x1, curves1.getCurveY(x1)));
            var p3 = getPointPosition.apply(new Vector2f(x0, curves1.getCurveY(x0)));

            graphics.fillTriangle(RenderPipelines.GUI, p0, p1, p2, ColorPattern.T_RED.color);
            graphics.fillTriangle(RenderPipelines.GUI, p2, p3, p0, ColorPattern.T_RED.color);
        }

        // render lines
        new CurveTexture(curves0).setColor(color).drawInternal(graphics, x, y, width, height);
        new CurveTexture(curves1).setColor(color).drawInternal(graphics, x, y, width, height);
    }

    /**
     * The random-curve band plus its two bounding polylines.
     * <p>
     * LDLib2 26.1 draws {@link com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture}s through
     * {@code GuiTextureRendererRegistry} (looked up by class, walking superclasses, falling back to a
     * renderer that only handles {@code GuiTexture} lambdas) — the interface no longer has a draw method,
     * so the 1.21-shaped {@code drawInternal} was dead code and this texture rendered nothing.
     */
    @LDLRegisterClient(name = "photon_random_curve", registry = "ldlib2:gui_texture_renderer")
    public static final class Renderer implements RegisteredGuiTextureRenderer<RandomCurveTexture, Renderer> {
        @Override
        public Class<RandomCurveTexture> type() {
            return RandomCurveTexture.class;
        }

        @Override
        public void draw(RandomCurveTexture texture, GUIContext context, float x, float y, float width, float height) {
            TransformTextureRenderer.draw(texture, context, x, y, width, height, RandomCurveTexture::drawInternal);
        }
    }
}
