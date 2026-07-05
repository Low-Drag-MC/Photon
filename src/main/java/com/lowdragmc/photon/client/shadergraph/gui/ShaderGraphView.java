package com.lowdragmc.photon.client.shadergraph.gui;

import com.lowdragmc.kilagraph.rendertype.gui.RenderTypeGraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import org.jetbrains.annotations.Nullable;

/**
 * The graph view for Photon shader graphs: KilaGraph's editor (canvas, node library, blackboard, shader
 * preview) minus the RenderType settings panel — a Photon graph's vertex format is fixed and its render
 * state (blend/cull/depth) lives on the emitter's {@code MaterialSetting}, so there is nothing to edit.
 */
public class ShaderGraphView extends RenderTypeGraphView {
    @Override
    protected boolean shouldShowSettingsPanel(@Nullable Graph graph) {
        return false;
    }
}
