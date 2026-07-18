package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import com.lowdragmc.lowdraglib2.client.shader.LDLibRenderTypes;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.mojang.blaze3d.systems.RenderSystem;
import lombok.Getter;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import oshi.util.tuples.Pair;

import java.util.List;
import java.util.function.Consumer;

/** Editor for a min/max pair of {@link ECBCurves} (series 0 = A, series 1 = B) with the area between them
 *  filled; see {@link AbstractCurveGraph} for the interaction model. */
public class RandomCurveGraph extends AbstractCurveGraph<Pair<ECBCurves, ECBCurves>> {
    @Getter
    protected Pair<ECBCurves, ECBCurves> value = new Pair<>(new ECBCurves(), new ECBCurves());

    public RandomCurveGraph() {
        refreshGraph();
    }

    @Override
    protected List<ECBCurves> allCurves() {
        return List.of(value.getA(), value.getB());
    }

    public RandomCurveGraph setOnCurveChangeListener(Consumer<Pair<ECBCurves, ECBCurves>> listener) {
        registerValueListener(listener);
        return this;
    }

    @Override
    public RandomCurveGraph setValue(@Nullable Pair<ECBCurves, ECBCurves> value, boolean notify) {
        if (value == null || value.getA() == null || value.getB() == null) return this;
        this.value = value;
        if (notify) {
            notifyListeners();
        }
        // always rebuild: the caller may have mutated the same instances in place (resource load / drop)
        refreshGraph();
        return this;
    }

    @Override
    protected void drawArea(GuiGraphics graphics, float x, float y, float width, float height) {
        if (value.getA().getSegments().isEmpty() || value.getB().getSegments().isEmpty()) return;
        var buffer = graphics.bufferSource().getBuffer(LDLibRenderTypes.guiOverlay());
        RenderSystem.disableDepthTest();

        var matrix = graphics.pose().last().pose();
        var count = width * 2;
        for (int i = 0; i < count; i++) {
            float x0 = i * 1f / count;
            float x1 = (i + 1) * 1f / count;

            var p0 = toScreen(new Vector2f(x0, value.getA().getCurveY(x0)), x, y, width, height);
            var p1 = toScreen(new Vector2f(x1, value.getA().getCurveY(x1)), x, y, width, height);
            var p2 = toScreen(new Vector2f(x1, value.getB().getCurveY(x1)), x, y, width, height);
            var p3 = toScreen(new Vector2f(x0, value.getB().getCurveY(x0)), x, y, width, height);

            buffer.addVertex(matrix, p0.x, p0.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p1.x, p1.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p2.x, p2.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p3.x, p3.y, 0.0f).setColor(ColorPattern.T_WHITE.color);

            buffer.addVertex(matrix, p3.x, p3.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p2.x, p2.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p1.x, p1.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
            buffer.addVertex(matrix, p0.x, p0.y, 0.0f).setColor(ColorPattern.T_WHITE.color);
        }
    }
}
