package com.lowdragmc.photon.client.shadergraph;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraph;
import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphModel;
import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderGraphCompiler;
import com.lowdragmc.kilagraph.rendertype.format.VertexFormatPresets;
import com.lowdragmc.kilagraph.rendertype.nodes.input.VertexColorNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.vertex.VertexAttributeInputNode;
import com.lowdragmc.kilagraph.rendertype.nodes.texture.TextureNode;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VertexPositionBlock;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.GraphNodeRegistry;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.BlockNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.graph.CustomGraphModelImpl;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.shadergraph.nodes.ParticlePositionBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Photon's shader graph: a {@link RenderTypeGraph} whose compile target is the Photon particle pipeline.
 * One graph serves every Photon render path — CPU-built quads/trails/beams and the GPU-instanced particle
 * paths — because the generated vertex shader reads its inputs through {@code photon:particle.glsl}'s
 * {@code getParticleData()} (see {@link PhotonShaderCompiler}), whose attribute layout is selected by the
 * {@code PARTICLE_INSTANCE}/{@code PARTICLE_MODEL_INSTANCE} defines at shader-variant build.
 *
 * <p>The vertex format is therefore fixed ({@code BLOCK} — what every Photon pass uses); render state
 * (blend/cull/depth) belongs to Photon's {@code MaterialSetting}, not the graph — so {@link Settings} are
 * constant here and the editor's RenderType settings panel is hidden ({@code ShaderGraphView}).</p>
 */
public class ShaderGraph extends RenderTypeGraph {
    /** Photon's own node registry: nodes annotated with {@code graphTypes = ShaderGraph.class}. */
    public static final GraphNodeRegistry NODE_REGISTRY =
            GraphNodeRegistry.create(Photon.id("shader_graph"), ShaderGraph.class);

    /**
     * KilaGraph nodes that don't apply to the Photon pipeline: raw vertex-attribute reads (inputs come
     * from {@code getParticleData()}, whose layout varies per instancing define) and KilaGraph's vertex
     * position block (replaced by {@link ParticlePositionBlock}'s fixed particle transform + offset).
     */
    private static final Set<Class<? extends Node>> EXCLUDED_NODES = Set.of(
            VertexAttributeInputNode.class,
            VertexPositionBlock.class);

    /**
     * The fixed compile settings: BLOCK vertex format (all Photon passes), QUADS, translucent-ish preview
     * state. Only {@code vertexFormatElements} feeds the generated GLSL; blend/depth/cull are used solely
     * by the editor preview's immediate draw (Photon's real draws take render state from MaterialSetting).
     */
    public static final Settings FIXED_SETTINGS = new Settings(
            VertexFormatPresets.BLOCK,
            Settings.VertexFormatMode.QUADS,
            Settings.BlendMode.TRANSLUCENT,
            Settings.DepthTest.LEQUAL,
            true,
            false,
            Settings.OutputTarget.MAIN,
            false,
            false);

    public ShaderGraph() {
        super();
    }

    public ShaderGraph(boolean initialize) {
        super(initialize);
    }

    @Override
    public Settings getSettings() {
        return FIXED_SETTINGS;
    }

    /** Settings are fixed for the Photon pipeline (see {@link #FIXED_SETTINGS}); edits are ignored. */
    @Override
    public void setSettings(Settings settings) {
    }

    @Override
    public ShaderGraphCompiler createCompiler() {
        return new PhotonShaderCompiler(this);
    }

    /** Local subgraphs ("create subgraph from selection" / dive-in) are Photon function graphs, so
     *  they carry Photon's node palette too. */
    @Override
    protected CustomGraphModelImpl createGraphModel() {
        return new RenderTypeGraphModel(this) {
            @Override
            public CustomGraphModelImpl createLocalSubgraphInstance() {
                return createLocalSubgraphInstance(PhotonShaderFunctionGraph.class);
            }
        };
    }

    /** All KilaGraph rendertype nodes (minus the excluded raw-attribute ones) plus Photon's own. */
    @Override
    public List<Class<? extends Node>> getSupportNodes() {
        var nodes = new ArrayList<Class<? extends Node>>();
        for (var node : RenderTypeGraph.NODE_REGISTRY.getNodeClasses()) {
            if (!EXCLUDED_NODES.contains(node)) nodes.add(node);
        }
        nodes.addAll(NODE_REGISTRY.getNodeClasses());
        return nodes;
    }

    /** Particles are lit by the baked lightmap (like vanilla particles), not per-vertex diffuse. */
    @Override
    protected String defaultVertexColorMode() {
        return VertexColorNode.MODE_BLOCK;
    }

    @Override
    protected Class<? extends BlockNode> defaultVertexPositionBlockClass() {
        return ParticlePositionBlock.class;
    }

    /** The default shader samples Photon's soft-circle particle texture instead of dirt. */
    @Override
    protected NodeModel createDefaultTextureSource() {
        NodeModel node = createNode(TextureNode.class, 48, -128);
        for (var opt : node.getNodeOptions()) {
            if (opt.id.equals("texture")) {
                var constant = node.getInputConstantsById().get(opt.portModel.getUniqueName());
                if (constant != null) {
                    constant.setValue(RenderTypeGraphTypes.Sampler2DValue.defaultValue()
                            .withLocation("photon:textures/particle/circle.png"));
                }
                node.defineNode();
                break;
            }
        }
        return node;
    }
}
