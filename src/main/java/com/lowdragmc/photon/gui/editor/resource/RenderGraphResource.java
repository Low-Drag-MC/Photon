package com.lowdragmc.photon.gui.editor.resource;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.editor.resource.BuiltinResourceProvider;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourceProvider;
import com.lowdragmc.lowdraglib2.editor.ui.resource.ResourceProviderContainer;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.variable.VariableKind;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphResource;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.GraphResourceProviderContainer;
import com.lowdragmc.lowdraglib2.nodegraphtookit.editor.IGraphReferenceResolver;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.GraphView;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.variable.VariableDeclarationModelBase;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonIcons;
import com.lowdragmc.photon.client.postfx.graph.PassSize;
import com.lowdragmc.photon.client.postfx.graph.PassSource;
import com.lowdragmc.photon.client.postfx.graph.RenderGraph;
import com.lowdragmc.photon.client.postfx.graph.RenderGraphCompiler;
import com.lowdragmc.photon.client.postfx.graph.SizeSpec;
import com.lowdragmc.photon.client.postfx.graph.gui.RenderGraphView;
import com.lowdragmc.photon.client.postfx.graph.nodes.CustomMaskInputNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.OutputNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.PassNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.SceneColorInputNode;
import com.lowdragmc.photon.client.postfx.graph.nodes.SceneDepthInputNode;
import com.lowdragmc.photon.client.postfx.runtime.CustomShaderPass;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.postfx.shadergraph.runtime.FullscreenGraphRuntime;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Photon editor resource for {@link RenderGraph}s — one resource = one post-processing effect.
 * Referenced by {@code IResourcePath} from the effect request API and (Phase 3) timeline
 * post-process clips; compiled/cached by {@code RenderGraphRuntime}.
 */
public class RenderGraphResource extends GraphResource<RenderGraph> {
    public static final RenderGraphResource INSTANCE = new RenderGraphResource();

    private static final String GRAPH_TAG = "graph";

    /** Transient path-selection hook for effect pickers (clip editors etc.), mirroring
     *  {@code ShaderGraphResource} — the stock selector callback only reports values. */
    @Nullable
    private Consumer<IResourcePath> pathSelectListener;

    protected RenderGraphResource() {}

    public void setPathSelectListener(@Nullable Consumer<IResourcePath> listener) {
        this.pathSelectListener = listener;
    }

    @Override
    public void buildBuiltin(BuiltinResourceProvider<CompoundTag> provider) {
        // PassNode resolves its fullscreen graph while defining ports — warm that resource chain
        // FIRST. Built cold (nested first-init mid-flight), the pass came up port-less and the
        // wires serialized without endpoints (observed: both builtin wires skipped on load).
        FullscreenGraphRuntime.get(BuiltinResourceProvider.TYPE.createFullPath("invert"));
        // SceneColor -> Pass(builtin invert fullscreen graph) -> Output: the smallest complete effect,
        // doubling as the render-graph-path smoke test (/photonfx test invert_effect).
        addVerified(provider, "invert_effect", this::buildInvertEffect);
        // the hand-written pass library (three.js-postprocessing-style, shaders/core/postfx/*)
        addVerified(provider, "grayscale", () -> buildShaderEffect("photon:postfx/grayscale"));
        addVerified(provider, "sepia", () -> buildShaderEffect("photon:postfx/sepia"));
        addVerified(provider, "brightness_contrast", () -> buildShaderEffect("photon:postfx/brightness_contrast"));
        addVerified(provider, "hue_saturation", () -> buildShaderEffect("photon:postfx/hue_saturation"));
        addVerified(provider, "vignette", () -> buildShaderEffect("photon:postfx/vignette"));
        addVerified(provider, "rgb_shift", () -> buildShaderEffect("photon:postfx/rgb_shift"));
        addVerified(provider, "pixelate", () -> buildShaderEffect("photon:postfx/pixelate"));
        addVerified(provider, "dot_screen", () -> buildShaderEffect("photon:postfx/dot_screen"));
        addVerified(provider, "film", () -> buildShaderEffect("photon:postfx/film"));
        addVerified(provider, "glitch", () -> buildShaderEffect("photon:postfx/glitch"));
        addVerified(provider, "gaussian_blur", this::buildGaussianBlur);
        addVerified(provider, "outline", this::buildOutline);
        addVerified(provider, "tint", () -> buildShaderEffect("photon:postfx/tint"));
        addVerified(provider, "sharpen", () -> buildShaderEffect("photon:postfx/sharpen"));
        addVerified(provider, "posterize", () -> buildShaderEffect("photon:postfx/posterize"));
        addVerified(provider, "radial_blur", () -> buildShaderEffect("photon:postfx/radial_blur"));
        addVerified(provider, "lens_distortion", () -> buildShaderEffect("photon:postfx/lens_distortion"));
        addVerified(provider, "bloom_effect", this::buildBloomEffect);
        addVerified(provider, "depth_of_field", this::buildDepthOfField);
        // CustomMask samples (per-object effects: flag an emitter renderer's writeCustomMask)
        addVerified(provider, "show_mask", this::buildShowMask);
        addVerified(provider, "mask_outline", this::buildMaskOutline);
    }

    /** Build + round-trip-verify a builtin (deserialize the serialized tag and compile it) —
     *  a broken builtin logs loudly instead of failing silently at first use; one retry covers
     *  first-touch initialization races. */
    private void addVerified(BuiltinResourceProvider<CompoundTag> provider, String name,
                             Supplier<RenderGraph> builder) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                var tag = serializeGraph(builder.get());
                RenderGraphCompiler.compile(null, deserializeGraph(tag));
                provider.addResource(name, tag);
                return;
            } catch (Exception e) {
                Photon.LOGGER.error("builtin '{}' failed round-trip verification (attempt {}): {}",
                        name, attempt, e.getMessage());
            }
        }
    }

    @Override
    public RenderGraph createGraph() {
        return new RenderGraph();
    }

    @Override
    public Supplier<? extends GraphView> getGraphViewFactory() {
        return RenderGraphView::new;
    }

    public CompoundTag serializeGraph(RenderGraph graph) {
        var root = new CompoundTag();
        root.put(GRAPH_TAG, PersistedParser.serializeNBT(graph.graphModel, Platform.getFrozenRegistry()));
        return root;
    }

    public RenderGraph deserializeGraph(CompoundTag tag) {
        var graph = new RenderGraph(false);
        var graphTag = tag.get(GRAPH_TAG) instanceof CompoundTag compound ? compound : tag;
        PersistedParser.deserializeNBT(graphTag, graph.graphModel, Platform.getFrozenRegistry());
        graph.restoreAfterDeserialize();
        return graph;
    }

    /** The resource's canonical (de)serialization pair — the editor container and all resolver
     *  paths route through these, so every load restores the fixed output node. */
    @Override
    public CompoundTag serializeGraphResource(RenderGraph graph) {
        return serializeGraph(graph);
    }

    @Override
    public RenderGraph deserializeGraphResource(CompoundTag tag,
            @Nullable IGraphReferenceResolver resolver) {
        var graph = new RenderGraph(false);
        graph.graphModel.setReferenceResolver(resolver);
        var graphTag = tag.get(GRAPH_TAG) instanceof CompoundTag compound ? compound : tag;
        PersistedParser.deserializeNBT(graphTag, graph.graphModel, Platform.getFrozenRegistry());
        graph.graphModel.setReferenceResolver(resolver);
        graph.restoreAfterDeserialize();
        return graph;
    }

    /** Tile selection reports the clicked path to {@link #setPathSelectListener}. */
    @Override
    public ResourceProviderContainer<CompoundTag> createResourceProviderContainer(IResourceProvider<CompoundTag> provider) {
        return new GraphResourceProviderContainer(this, provider) {
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
        return PhotonIcons.BLOOM;
    }

    @Override
    public String getName() {
        return "render_graph";
    }

    private RenderGraph buildInvertEffect() {
        var graph = new RenderGraph();
        var sceneColor = findSceneColor(graph);
        var pass = graph.addNode(PassNode.class, 140, -80);
        RenderGraph.setNodeOption(pass, PassNode.OPTION_SOURCE, PassSource
                .ofGraph(BuiltinResourceProvider.TYPE.createFullPath("invert").getPathWithType()));
        // splice the pass into the starter's direct SceneColor -> Output wire
        var colorPort = graph.getOutputNodeModel().getInputsById().get(OutputNode.COLOR_PORT);
        graph.graphModel.deleteWires(colorPort.getConnectedWires());
        graph.graphModel.createWire(
                pass.getInputsById().get(FullscreenShaderGraph.DEFAULT_INPUT),
                sceneColor.getOutputsById().get(SceneColorInputNode.OUTPUT_PORT));
        graph.graphModel.createWire(colorPort, pass.getOutputsById().get(PassNode.OUTPUT_PORT));
        return graph;
    }

    /** SceneColor -> Pass(hand-written shader, DiffuseSampler input) -> Output, with every shader
     *  uniform promoted to a blackboard parameter (so clips/API can drive them). */
    private RenderGraph buildShaderEffect(String shaderLocation) {
        var graph = new RenderGraph();
        var sceneColor = findSceneColor(graph);
        var pass = addShaderPass(graph, shaderLocation, 140, -80);
        var colorPort = graph.getOutputNodeModel().getInputsById().get(OutputNode.COLOR_PORT);
        graph.graphModel.deleteWires(colorPort.getConnectedWires());
        graph.graphModel.createWire(pass.getInputsById().get(MAIN_SAMPLER),
                sceneColor.getOutputsById().get(SceneColorInputNode.OUTPUT_PORT));
        graph.graphModel.createWire(colorPort, pass.getOutputsById().get(PassNode.OUTPUT_PORT));
        promoteUniformsToParams(graph, pass, shaderLocation, -40, 40);
        return graph;
    }

    /** SceneColor -> blur_h -> blur_v -> Output: the separable-gaussian two-pass sample. One
     *  shared Radius parameter drives both passes (per-pass promotion would collide on the name). */
    private RenderGraph buildGaussianBlur() {
        var graph = new RenderGraph();
        var sceneColor = findSceneColor(graph);
        var horizontal = addShaderPass(graph, "photon:postfx/blur_h", 60, -80);
        var vertical = addShaderPass(graph, "photon:postfx/blur_v", 240, -80);
        var colorPort = graph.getOutputNodeModel().getInputsById().get(OutputNode.COLOR_PORT);
        graph.graphModel.deleteWires(colorPort.getConnectedWires());
        graph.graphModel.createWire(horizontal.getInputsById().get(MAIN_SAMPLER),
                sceneColor.getOutputsById().get(SceneColorInputNode.OUTPUT_PORT));
        graph.graphModel.createWire(vertical.getInputsById().get(MAIN_SAMPLER),
                horizontal.getOutputsById().get(PassNode.OUTPUT_PORT));
        graph.graphModel.createWire(colorPort, vertical.getOutputsById().get(PassNode.OUTPUT_PORT));
        var radius = (VariableDeclarationModelBase) graph.graphModel.createVariable(
                "Radius", TypeHandles.FLOAT, 2.0f, VariableKind.INPUT);
        var radiusNode = graph.graphModel.createVariableNode(radius, new Vector2f(-40, 40), null, null);
        graph.graphModel.createWire(horizontal.getInputsById().get("Radius"), radiusNode.getOutputPort());
        graph.graphModel.createWire(vertical.getInputsById().get("Radius"), radiusNode.getOutputPort());
        return graph;
    }

    /** SceneColor -> bright(×0.5, Threshold) -> blur pair -> add_mix(scene + blurred × Strength):
     *  the multi-pass bloom sample (also demonstrates INPUT_RELATIVE sizing). */
    private RenderGraph buildBloomEffect() {
        var graph = new RenderGraph();
        var sceneColor = findSceneColor(graph);
        var bright = addShaderPass(graph, "photon:postfx/bright", 40, -80);
        RenderGraph.setNodeOption(bright, PassNode.OPTION_SIZE, PassSize.DEFAULT
                .withMode(SizeSpec.Mode.SCREEN_RELATIVE).withScale(0.5f));
        graph.graphModel.createWire(bright.getInputsById().get(MAIN_SAMPLER),
                sceneColor.getOutputsById().get(SceneColorInputNode.OUTPUT_PORT));
        var blurred = addBlurChain(graph, bright, PassNode.OUTPUT_PORT, 220, -80, 1f);
        var composite = addShaderPass(graph, "photon:postfx/add_mix", 580, -80);
        var colorPort = graph.getOutputNodeModel().getInputsById().get(OutputNode.COLOR_PORT);
        graph.graphModel.deleteWires(colorPort.getConnectedWires());
        graph.graphModel.createWire(composite.getInputsById().get(MAIN_SAMPLER),
                sceneColor.getOutputsById().get(SceneColorInputNode.OUTPUT_PORT));
        graph.graphModel.createWire(composite.getInputsById().get("AddSampler"),
                blurred.getOutputsById().get(PassNode.OUTPUT_PORT));
        graph.graphModel.createWire(colorPort, composite.getOutputsById().get(PassNode.OUTPUT_PORT));
        promoteUniformsToParams(graph, bright, "photon:postfx/bright", -40, 60);
        promoteUniformsToParams(graph, composite, "photon:postfx/add_mix", -40, 120);
        return graph;
    }

    /** SceneColor -> half-res blur pair; dof_composite blends sharp/blurred by linearized depth
     *  distance from the autofocus point (depth under Center). */
    private RenderGraph buildDepthOfField() {
        var graph = new RenderGraph();
        var sceneColor = findSceneColor(graph);
        var blurred = addBlurChain(graph, sceneColor, SceneColorInputNode.OUTPUT_PORT, 60, -180, 0.5f);
        var depth = graph.addNode(SceneDepthInputNode.class, -160, 40);
        var composite = addShaderPass(graph, "photon:postfx/dof_composite", 460, -80);
        var colorPort = graph.getOutputNodeModel().getInputsById().get(OutputNode.COLOR_PORT);
        graph.graphModel.deleteWires(colorPort.getConnectedWires());
        graph.graphModel.createWire(composite.getInputsById().get(MAIN_SAMPLER),
                sceneColor.getOutputsById().get(SceneColorInputNode.OUTPUT_PORT));
        graph.graphModel.createWire(composite.getInputsById().get("BlurSampler"),
                blurred.getOutputsById().get(PassNode.OUTPUT_PORT));
        graph.graphModel.createWire(composite.getInputsById().get("DepthSampler"),
                depth.getOutputsById().get(SceneDepthInputNode.OUTPUT_PORT));
        graph.graphModel.createWire(colorPort, composite.getOutputsById().get(PassNode.OUTPUT_PORT));
        promoteUniformsToParams(graph, composite, "photon:postfx/dof_composite", -40, 140);
        return graph;
    }

    /** CustomMaskInput -> show_mask pass -> Output: the mask debug view. */
    private RenderGraph buildShowMask() {
        var graph = new RenderGraph();
        var mask = graph.addNode(CustomMaskInputNode.class, -160, 20);
        var pass = addShaderPass(graph, "photon:postfx/show_mask", 140, -80);
        var colorPort = graph.getOutputNodeModel().getInputsById().get(OutputNode.COLOR_PORT);
        graph.graphModel.deleteWires(colorPort.getConnectedWires());
        graph.graphModel.createWire(pass.getInputsById().get("MaskSampler"),
                mask.getOutputsById().get(CustomMaskInputNode.OUTPUT_PORT));
        graph.graphModel.createWire(colorPort, pass.getOutputsById().get(PassNode.OUTPUT_PORT));
        return graph;
    }

    /** SceneColor + CustomMaskInput -> mask_outline pass -> Output: the per-object outline sample
     *  (MaskValue/OutlineColor/Thickness promoted, so clips can pick the group and animate color). */
    private RenderGraph buildMaskOutline() {
        var graph = buildShaderEffect("photon:postfx/mask_outline");
        var mask = graph.addNode(CustomMaskInputNode.class, -160, 20);
        NodeModel pass = null;
        for (var model : graph.graphModel.getNodeModels()) {
            if (model instanceof NodeModel nm && model instanceof ICustomNodeModel custom
                    && custom.getNode() instanceof PassNode) {
                pass = nm;
                break;
            }
        }
        graph.graphModel.createWire(pass.getInputsById().get("MaskSampler"),
                mask.getOutputsById().get(CustomMaskInputNode.OUTPUT_PORT));
        return graph;
    }

    /** blur_h(INPUT_RELATIVE × {@code firstScale}) -> blur_v(matching), fed from {@code source}'s
     *  {@code sourcePort}; ONE shared Radius parameter drives both. Returns the blur_v node. */
    private NodeModel addBlurChain(RenderGraph graph, NodeModel source, String sourcePort,
                                   float x, float y, float firstScale) {
        var horizontal = addShaderPass(graph, "photon:postfx/blur_h", (int) x, (int) y);
        RenderGraph.setNodeOption(horizontal, PassNode.OPTION_SIZE, PassSize.DEFAULT
                .withMode(SizeSpec.Mode.INPUT_RELATIVE)
                .withScale(firstScale).withInputPort(MAIN_SAMPLER));
        var vertical = addShaderPass(graph, "photon:postfx/blur_v", (int) x + 180, (int) y);
        RenderGraph.setNodeOption(vertical, PassNode.OPTION_SIZE, PassSize.DEFAULT
                .withMode(SizeSpec.Mode.INPUT_RELATIVE)
                .withScale(1f).withInputPort(MAIN_SAMPLER));
        graph.graphModel.createWire(horizontal.getInputsById().get(MAIN_SAMPLER),
                source.getOutputsById().get(sourcePort));
        graph.graphModel.createWire(vertical.getInputsById().get(MAIN_SAMPLER),
                horizontal.getOutputsById().get(PassNode.OUTPUT_PORT));
        var radius = (VariableDeclarationModelBase) graph.graphModel.createVariable(
                "Radius", TypeHandles.FLOAT, 2.0f, VariableKind.INPUT);
        var radiusNode = graph.graphModel.createVariableNode(radius, new Vector2f(x - 40, y + 100), null, null);
        graph.graphModel.createWire(horizontal.getInputsById().get("Radius"), radiusNode.getOutputPort());
        graph.graphModel.createWire(vertical.getInputsById().get("Radius"), radiusNode.getOutputPort());
        return vertical;
    }

    /**
     * Promote every introspected shader uniform into an INPUT blackboard variable wired to its
     * pass port — that's what makes builtin effects parameterizable from clips and the submit API
     * (the schema IS the blackboard). A 4-component uniform whose name mentions "color" becomes a
     * COLOR parameter (ARGB, gradient-sampled in clips) instead of a plain VEC4.
     */
    private static void promoteUniformsToParams(RenderGraph graph, NodeModel pass, String shaderLocation,
                                                float x, float startY) {
        var info = CustomShaderPass.getInfo(shaderLocation);
        if (info == null) return;
        float y = startY;
        for (var uniform : info.uniforms()) {
            var port = pass.getInputsById().get(uniform.name());
            if (port == null || port.isConnected()) continue;
            var defaults = uniform.defaults();
            TypeHandle type;
            Object defaultValue;
            switch (uniform.count()) {
                case 1 -> {
                    type = TypeHandles.FLOAT;
                    defaultValue = defaults[0];
                }
                case 2 -> {
                    type = RenderTypeGraphTypes.VEC2;
                    defaultValue = new Vector2f(defaults[0], defaults[1]);
                }
                case 3 -> {
                    type = RenderTypeGraphTypes.VEC3;
                    defaultValue = new Vector3f(defaults[0], defaults[1], defaults[2]);
                }
                default -> {
                    if (uniform.name().toLowerCase(Locale.ROOT).contains("color")) {
                        type = TypeHandles.COLOR;
                        defaultValue = packArgb(defaults);
                    } else {
                        type = RenderTypeGraphTypes.VEC4;
                        defaultValue = new Vector4f(defaults[0], defaults[1], defaults[2], defaults[3]);
                    }
                }
            }
            var declaration = (VariableDeclarationModelBase) graph.graphModel.createVariable(
                    uniform.name(), type, defaultValue, VariableKind.INPUT);
            var node = graph.graphModel.createVariableNode(declaration, new Vector2f(x, y), null, null);
            if (graph.graphModel.createWire(port, node.getOutputPort()) == null) {
                Photon.LOGGER.warn("builtin effect '{}': parameter '{}' ({}) could not wire to its port",
                        shaderLocation, uniform.name(), type);
            }
            y += 44;
        }
    }

    /** json rgba floats -> ARGB int (the COLOR blackboard value form). */
    private static int packArgb(float[] rgba) {
        int r = Math.round(Math.clamp(rgba[0], 0f, 1f) * 255f);
        int g = Math.round(Math.clamp(rgba[1], 0f, 1f) * 255f);
        int b = Math.round(Math.clamp(rgba[2], 0f, 1f) * 255f);
        int a = Math.round(Math.clamp(rgba[3], 0f, 1f) * 255f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** The outline pass additionally wires scene depth into its DepthSampler. */
    private RenderGraph buildOutline() {
        var graph = buildShaderEffect("photon:postfx/outline");
        var depth = graph.addNode(SceneDepthInputNode.class,
                -160, 20);
        NodeModel pass = null;
        for (var model : graph.graphModel.getNodeModels()) {
            if (model instanceof NodeModel nm && model instanceof ICustomNodeModel custom
                    && custom.getNode() instanceof PassNode) {
                pass = nm;
                break;
            }
        }
        graph.graphModel.createWire(pass.getInputsById().get("DepthSampler"),
                depth.getOutputsById().get(
                        SceneDepthInputNode.OUTPUT_PORT));
        return graph;
    }

    /** The main-input sampler name every builtin pass shader uses. */
    private static final String MAIN_SAMPLER = "DiffuseSampler";

    private static NodeModel addShaderPass(RenderGraph graph, String shaderLocation, int x, int y) {
        var pass = graph.addNode(PassNode.class, x, y);
        RenderGraph.setNodeOption(pass, PassNode.OPTION_SOURCE,
                PassSource.ofShader(shaderLocation));
        return pass;
    }

    private static NodeModel findSceneColor(RenderGraph graph) {
        for (var model : graph.graphModel.getNodeModels()) {
            if (model instanceof NodeModel nm && model instanceof ICustomNodeModel custom
                    && custom.getNode() instanceof SceneColorInputNode) {
                return nm;
            }
        }
        return graph.addNode(SceneColorInputNode.class, -160, -80);
    }
}
