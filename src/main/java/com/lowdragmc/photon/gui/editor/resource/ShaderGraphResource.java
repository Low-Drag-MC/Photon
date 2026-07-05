package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.kilagraph.editor.RenderTypeGraphResource;
import com.lowdragmc.kilagraph.rendertype.RenderTypeGraph;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;
import com.lowdragmc.photon.client.shadergraph.gui.ShaderGraphView;
import net.minecraft.nbt.CompoundTag;

import java.util.function.Supplier;

/**
 * The Photon editor resource for {@link ShaderGraph}s. Reuses KilaGraph's graph (de)serialization and
 * resource-container plumbing ({@code RenderTypeGraphResourceProviderContainer}: double-click opens the
 * graph editor view, external subgraph references resolve across the project's resources); only the graph
 * type, view and identity differ. {@code ShaderGraphMaterial} resolves a graph from this resource by
 * {@code IResourcePath} and compiles it through the Photon pipeline.
 */
public class ShaderGraphResource extends RenderTypeGraphResource {
    public static final ShaderGraphResource INSTANCE = new ShaderGraphResource();

    /**
     * Fired (with the clicked path) whenever a shader-graph tile is selected in ANY container of this
     * resource — set transiently by {@code ShaderGraphMaterial}'s selector dialog to receive the chosen
     * <em>path</em> (the stock selector callback only reports the resource value), cleared on close.
     */
    @org.jetbrains.annotations.Nullable
    private java.util.function.Consumer<com.lowdragmc.lowdraglib2.editor.resource.IResourcePath> pathSelectListener;

    protected ShaderGraphResource() {}

    public void setPathSelectListener(
            @org.jetbrains.annotations.Nullable java.util.function.Consumer<com.lowdragmc.lowdraglib2.editor.resource.IResourcePath> listener) {
        this.pathSelectListener = listener;
    }

    @Override
    public void buildBuiltin(com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider<CompoundTag> provider) {
        // The default particle shader (texture x lit particle color -> fog -> color/alpha) as a starter.
        provider.addResource("default_particle", serializeGraph(createGraph()));
    }

    @Override
    public ShaderGraph createGraph() {
        return new ShaderGraph();
    }

    @Override
    protected RenderTypeGraph newGraphForLoad() {
        return new ShaderGraph(false);
    }

    @Override
    public Supplier<? extends GraphView> getGraphViewFactory() {
        return ShaderGraphView::new;
    }

    /**
     * Dragging a shader-graph tile carries a {@link ShaderGraphMaterial} referencing the path — an
     * {@code IMaterial}, so every material slot (emitter material configurators) accepts the drop as-is.
     * The base container's {@code DraggingGraph} payload is only useful for graph-into-graph embedding,
     * which top-level shader graphs don't support anyway (only ShaderFunctionGraphs embed).
     * Tile selection additionally reports the clicked <em>path</em> to {@link #setPathSelectListener}.
     */
    @Override
    public ResourceProviderContainer<CompoundTag> createResourceProviderContainer(IResourceProvider<CompoundTag> provider) {
        var container = new com.lowdragmc.kilagraph.editor.RenderTypeGraphResourceProviderContainer(this, provider) {
            @Override
            public void selectResource(com.lowdragmc.lowdraglib2.editor.resource.IResourcePath resourcePath) {
                super.selectResource(resourcePath);
                if (pathSelectListener != null && resourcePath != null && provider.hasResource(resourcePath)) {
                    pathSelectListener.accept(resourcePath);
                }
            }
        };
        container.setOnDragProvider(ShaderGraphMaterial::new);
        return container;
    }

    @Override
    public IGuiTexture getIcon() {
        return Icons.WIDGET_CUSTOM;
    }

    @Override
    public String getName() {
        return "shader_graph";
    }
}
