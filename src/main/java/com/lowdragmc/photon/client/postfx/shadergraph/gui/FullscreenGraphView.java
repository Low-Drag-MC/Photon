package com.lowdragmc.photon.client.postfx.shadergraph.gui;

import com.lowdragmc.kilagraph.rendertype.gui.RenderTypeGraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import org.jetbrains.annotations.Nullable;

/**
 * The graph view for fullscreen post-processing graphs: KilaGraph's editor minus the RenderType
 * settings panel — a fullscreen graph's vertex format is fixed (bare NDC quad) and its render state
 * is owned by the effect executor, so there is nothing to edit.
 */
public class FullscreenGraphView extends RenderTypeGraphView {
    @Override
    protected boolean shouldShowSettingsPanel(@Nullable Graph graph) {
        return false;
    }
}
