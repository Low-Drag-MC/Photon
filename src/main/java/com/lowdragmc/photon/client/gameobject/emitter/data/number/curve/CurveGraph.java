package com.lowdragmc.photon.client.gameobject.emitter.data.number.curve;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** Editor for a single {@link ECBCurves}; see {@link AbstractCurveGraph} for the interaction model. */
public class CurveGraph extends AbstractCurveGraph<ECBCurves> {
    @Getter
    protected ECBCurves value = new ECBCurves();

    public CurveGraph() {
        refreshGraph();
    }

    @Override
    protected List<ECBCurves> allCurves() {
        return List.of(value);
    }

    public CurveGraph setOnCurveChangeListener(Consumer<ECBCurves> listener) {
        registerValueListener(listener);
        return this;
    }

    @Override
    public CurveGraph setValue(@Nullable ECBCurves value, boolean notify) {
        if (value == null) return this;
        this.value = value;
        if (notify) {
            notifyListeners();
        }
        // always rebuild: the caller may have mutated the same instance in place (resource load / drop)
        refreshGraph();
        return this;
    }
}
