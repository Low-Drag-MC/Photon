package com.lowdragmc.photon.client.postfx.graph.nodes;

import com.lowdragmc.kilagraph.graph.util.INodeDescription;
import com.lowdragmc.kilagraph.graph.util.NodeDescriptions;
import com.lowdragmc.kilagraph.graph.util.NodeTooltipHelper;
import com.lowdragmc.kilagraph.rendertype.compiler.NodeDisplayNames;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for the effect graph's nodes. They are plain LDLib2 {@link Node}s (the effect graph has no
 * compiler-facing node API the way the shader graph does), so this only carries what every one of them
 * wants from KilaGraph's node conventions — all keyed off the {@code NodeAttribute} name:
 * <ul>
 *   <li>the display name from the {@code <name>} lang key;</li>
 *   <li>the hover tooltip from {@code kg.node.<name>.tooltip};</li>
 *   <li>the item library's documentation panel, assembled from the {@code kg.node.<name>.*} lang keys
 *       plus the {@link INodeDescription} hooks — see {@link NodeDescriptions}.</li>
 * </ul>
 */
public abstract class RenderGraphNode extends Node implements INodeDescription {

    @Override
    public void setImplementation(NodeModel nodeModel) {
        super.setImplementation(nodeModel);
        NodeTooltipHelper.apply(nodeModel, NodeTooltipHelper.defaultTooltip(this));
    }

    @Override
    public Component getDisplayName() {
        return NodeDisplayNames.fromAttribute(this);
    }

    @Override
    @Nullable
    public UIElement createDescriptionUI() {
        return NodeDescriptions.build(this);
    }
}
