package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import net.minecraft.client.renderer.RenderPipelines;
import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import lombok.Getter;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
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
    protected void drawArea(GUIContext graphics, float x, float y, float width, float height) {
        if (value.getA().getSegments().isEmpty() || value.getB().getSegments().isEmpty()) return;
        // 26.1: the between-curves band as fillTriangle pairs (GUI render states, no immediate buffer)
        var count = width * 2;
        for (int i = 0; i < count; i++) {
            float x0 = i * 1f / count;
            float x1 = (i + 1) * 1f / count;

            var p0 = toScreen(new Vector2f(x0, value.getA().getCurveY(x0)), x, y, width, height);
            var p1 = toScreen(new Vector2f(x1, value.getA().getCurveY(x1)), x, y, width, height);
            var p2 = toScreen(new Vector2f(x1, value.getB().getCurveY(x1)), x, y, width, height);
            var p3 = toScreen(new Vector2f(x0, value.getB().getCurveY(x0)), x, y, width, height);

            graphics.fillTriangle(RenderPipelines.GUI, p0, p1, p2, ColorPattern.T_WHITE.color);
            graphics.fillTriangle(RenderPipelines.GUI, p2, p3, p0, ColorPattern.T_WHITE.color);
        }
    }
}
