package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.kilagraph.editor.RenderTypeGraphResource;
import com.lowdragmc.kilagraph.editor.RenderTypeGraphResourceProviderContainer;
import com.lowdragmc.kilagraph.rendertype.RenderTypeGraph;
import com.lowdragmc.kilagraph.rendertype.nodes.artistic.adjustment.InvertColorsNode;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.postfx.shadergraph.gui.FullscreenGraphView;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Photon editor resource for {@link FullscreenShaderGraph}s (the shader of one post-processing
 * pass). Reuses KilaGraph's graph (de)serialization and resource-container plumbing; effect render
 * graphs reference a fullscreen graph from here by {@code IResourcePath} and compile it through
 * {@code FullscreenGraphRuntime}.
 */
public class FullscreenShaderGraphResource extends RenderTypeGraphResource {
    public static final FullscreenShaderGraphResource INSTANCE = new FullscreenShaderGraphResource();

    /**
     * Fired (with the clicked path) whenever a fullscreen-graph tile is selected in ANY container of
     * this resource — set transiently by pass selector dialogs to receive the chosen <em>path</em>
     * (the stock selector callback only reports values), cleared on close.
     */
    @Nullable
    private Consumer<IResourcePath> pathSelectListener;

    protected FullscreenShaderGraphResource() {}

    public void setPathSelectListener(@Nullable Consumer<IResourcePath> listener) {
        this.pathSelectListener = listener;
    }

    @Override
    public void buildBuiltin(BuiltinResourceProvider<CompoundTag> provider) {
        // Input -> sample -> output: the pass every effect starts from.
        provider.addResource("passthrough", serializeGraph(createGraph()));
        // Input -> sample -> invert -> output: the phase-1 smoke-test effect.
        provider.addResource("invert", serializeGraph(new BuiltinInvertGraph()));
    }

    @Override
    public FullscreenShaderGraph createGraph() {
        return new FullscreenShaderGraph();
    }

    @Override
    protected RenderTypeGraph newGraphForLoad() {
        return new FullscreenShaderGraph(false);
    }

    @Override
    public Supplier<? extends GraphView> getGraphViewFactory() {
        return FullscreenGraphView::new;
    }

    /** Double-click opens the graph editor; tile selection additionally reports the clicked path to
     *  {@link #setPathSelectListener} (no drag payload — fullscreen graphs are referenced by effect
     *  passes, not dropped into material slots). */
    @Override
    public ResourceProviderContainer<CompoundTag> createResourceProviderContainer(IResourceProvider<CompoundTag> provider) {
        return new RenderTypeGraphResourceProviderContainer(this, provider) {
            @Override
            public void selectResource(IResourcePath resourcePath) {
                super.selectResource(resourcePath);
                if (pathSelectListener != null && resourcePath != null && provider.hasResource(resourcePath)) {
                    pathSelectListener.accept(resourcePath);
                }
            }
        };
    }

    @Override
    public IGuiTexture getIcon() {
        return PhotonIcons.FULL_SCREEN_GRAPH;
    }

    @Override
    public String getName() {
        return "fullscreen_graph";
    }

    /** The builtin invert starter: the default passthrough with an InvertColors spliced before output. */
    private static final class BuiltinInvertGraph extends FullscreenShaderGraph {
        @Override
        protected void wireDefaultOutput(NodeModel textureSample, NodeModel output) {
            var invert = createNode(InvertColorsNode.class, 432, -128);
            graphModel.createWire(invert.getInputsById().get("in"), textureSample.getOutputsById().get("color"));
            graphModel.createWire(output.getInputsById().get("color"), invert.getOutputsById().get("out"));
        }
    }
}
