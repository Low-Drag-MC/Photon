package com.lowdragmc.photon.client.postfx.shadergraph;

import com.lowdragmc.kilagraph.rendertype.RenderTypeGraph;
import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderGraphCompiler;
import com.lowdragmc.kilagraph.rendertype.nodes.fog.ApplyFogNode;
import com.lowdragmc.kilagraph.rendertype.nodes.fog.FogCylindricalDistanceNode;
import com.lowdragmc.kilagraph.rendertype.nodes.fog.FogSphericalDistanceNode;
import com.lowdragmc.kilagraph.rendertype.nodes.fog.FogUboNode;
import com.lowdragmc.kilagraph.rendertype.nodes.fog.LinearFogValueNode;
import com.lowdragmc.kilagraph.rendertype.nodes.fog.TotalFogValueNode;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentAlphaBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentAlphaDiscardBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentBaseColorBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.fragment.FragmentEmissionBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.input.NormalNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.PositionNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.VertexColorNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.ViewDirectionNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.fragment.CylindricalDistanceFragmentInputNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.fragment.SphericalDistanceFragmentInputNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.vertex.InstanceIdNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.vertex.VertexAttributeInputNode;
import com.lowdragmc.kilagraph.rendertype.nodes.input.vertex.VertexIdNode;
import com.lowdragmc.kilagraph.rendertype.nodes.lighting.LightUboNode;
import com.lowdragmc.kilagraph.rendertype.nodes.lighting.MixLightNode;
import com.lowdragmc.kilagraph.rendertype.nodes.math.vector.FresnelNode;
import com.lowdragmc.kilagraph.rendertype.nodes.math.vector.TransformNode;
import com.lowdragmc.kilagraph.rendertype.nodes.scene.GlobalsUboNode;
import com.lowdragmc.kilagraph.rendertype.nodes.scene.SceneColorNode;
import com.lowdragmc.kilagraph.rendertype.nodes.scene.SceneDepthNode;
import com.lowdragmc.kilagraph.rendertype.nodes.scene.ScreenNode;
import com.lowdragmc.kilagraph.rendertype.nodes.scene.ScreenPositionNode;
import com.lowdragmc.kilagraph.rendertype.nodes.texture.LightMapTextureNode;
import com.lowdragmc.kilagraph.rendertype.nodes.texture.OverlayTextureNode;
import com.lowdragmc.kilagraph.rendertype.nodes.texture.SamplerTexture2DNode;
import com.lowdragmc.kilagraph.rendertype.nodes.transform.DynamicTransformsUboNode;
import com.lowdragmc.kilagraph.rendertype.nodes.transform.ProjectionFromPositionNode;
import com.lowdragmc.kilagraph.rendertype.nodes.transform.ProjectionUboNode;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VaryingCustomFloatBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VaryingCustomVec2Block;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VaryingCustomVec3Block;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VaryingCustomVec4Block;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VertexModelNormalBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VertexModelPositionBlock;
import com.lowdragmc.kilagraph.rendertype.nodes.vertex.VertexPositionBlock;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.GraphNodeRegistry;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.BlockNode;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.variable.VariableKind;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.GraphCommands;
import com.lowdragmc.lowdraglib2.nodegraphtookit.gui.command.IGraphCommand;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.SpawnFlags;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ContextNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.CustomBlockNodeModelImpl;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.variable.VariableDeclarationModelBase;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.postfx.shadergraph.nodes.FullscreenOutputBlock;
import com.lowdragmc.photon.client.postfx.shadergraph.nodes.FullscreenPositionBlock;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The full-screen post-processing compile target: a {@link RenderTypeGraph} whose vertex stage is a fixed
 * pass-through NDC quad ({@link FullscreenPositionBlock}) and whose fragment stage writes one plain color
 * ({@link FullscreenOutputBlock}). One graph = the shader of ONE post-processing pass (e.g. one bloom
 * downsample); passes are chained by the effect render graph, which binds the graph's {@code SAMPLER2D}
 * blackboard variables to render targets at dispatch.
 *
 * <p><b>Pure-function rule</b>: a fullscreen graph never samples the scene implicitly — KilaGraph's
 * Scene Color/Depth nodes are excluded; scene textures arrive through declared {@code SAMPLER2D} inputs
 * wired by the effect graph. Anything vertex-format/fog/lighting/camera-bound is excluded too: the draw
 * is a bare NDC quad with no matrices, normals or fog state bound.</p>
 */
public class FullscreenShaderGraph extends RenderTypeGraph {
    /** Photon's fullscreen node registry: nodes annotated with {@code graphTypes = FullscreenShaderGraph.class}. */
    public static final GraphNodeRegistry NODE_REGISTRY =
            GraphNodeRegistry.create(Photon.id("fullscreen_graph"), FullscreenShaderGraph.class);

    /** The default input variable name of the starter graph — the pass's primary source texture. */
    public static final String DEFAULT_INPUT = "Input";

    /**
     * KilaGraph nodes that don't apply to a bare fullscreen pass: vertex-format-bound inputs (only
     * {@code Position} exists), geometry-derived transforms and Minecraft's per-draw uniform blocks
     * (nothing binds them here), fog and
     * lighting (no such state), overlay/lightmap (vanilla geometry concerns), scene capture (the
     * pure-function rule), and the particle fragment blocks ({@link FullscreenOutputBlock} replaces them).
     */
    private static final Set<Class<? extends Node>> EXCLUDED_NODES = Set.of(
            // vertex stage is fixed: no raw attributes, position/normal displacement or custom varyings
            VertexAttributeInputNode.class, VertexPositionBlock.class,
            VertexModelPositionBlock.class, VertexModelNormalBlock.class,
            VaryingCustomFloatBlock.class, VaryingCustomVec2Block.class,
            VaryingCustomVec3Block.class, VaryingCustomVec4Block.class,
            VertexIdNode.class, InstanceIdNode.class,
            // vertex-format-bound inputs (only Position exists on the quad)
            VertexColorNode.class, PositionNode.class, NormalNode.class, ViewDirectionNode.class,
            // Geometry-derived spaces: a fullscreen quad has no object to transform, so anything that
            // needs a surface position/normal is meaningless here.
            TransformNode.class, FresnelNode.class, ProjectionFromPositionNode.class,
            // MINECRAFT's per-draw blocks: a fullscreen pass never binds them (PhotonFullscreenPass.draw
            // deliberately skips bindDefaultUniforms — its javadoc says why), so a graph referencing one would
            // declare a uniform nothing fills and fail draw validation. KilaGraph's OWN blocks are a
            // different matter: RenderTypeGraphMaterial.bindCustomUniforms binds every one of them, which
            // is why CameraNode and KGTransformsUboNode are allowed — a camera's planes and basis are
            // properties of the VIEW, not of the geometry, and a depth-reading pass legitimately needs
            // them (linearising the depth buffer is impossible without near/far).
            DynamicTransformsUboNode.class, ProjectionUboNode.class,
            // fog + lighting state doesn't exist here
            ApplyFogNode.class, FogUboNode.class, FogCylindricalDistanceNode.class,
            FogSphericalDistanceNode.class, LinearFogValueNode.class, TotalFogValueNode.class,
            CylindricalDistanceFragmentInputNode.class, SphericalDistanceFragmentInputNode.class,
            LightUboNode.class, MixLightNode.class,
            // vanilla geometry textures
            LightMapTextureNode.class, OverlayTextureNode.class,
            // pure-function rule: scene textures come through declared inputs, not implicit capture
            SceneColorNode.class, SceneDepthNode.class, ScreenNode.class, ScreenPositionNode.class,
            GlobalsUboNode.class,
            // particle-pipeline fragment blocks — FullscreenOutputBlock is the single output
            FragmentBaseColorBlock.class, FragmentAlphaBlock.class,
            FragmentEmissionBlock.class, FragmentAlphaDiscardBlock.class
    );

    /**
     * The fixed compile settings: a bare {@code Position} vertex format drawn as the ±1 NDC quad by the
     * executor's fullscreen blit. Only {@code vertexFormatElements} feeds the generated GLSL; the executor
     * sets its own render state (no depth, no blend) per chain.
     */
    public static final Settings FIXED_SETTINGS = new Settings(
            List.of("position"),
            Settings.VertexFormatMode.QUADS,
            Settings.BlendMode.OPAQUE,
            Settings.DepthTest.NONE,
            false,
            false,
            Settings.OutputTarget.MAIN,
            false,
            false);

    public FullscreenShaderGraph() {
        super();
    }

    public FullscreenShaderGraph(boolean initialize) {
        super(initialize);
    }

    @Override
    public Settings getSettings() {
        return FIXED_SETTINGS;
    }

    /** Settings are fixed for the fullscreen pipeline; edits are ignored. */
    @Override
    public void setSettings(Settings settings) {
    }

    @Override
    public ShaderGraphCompiler createCompiler() {
        return new PhotonFullscreenCompiler(this);
    }

    /** All KilaGraph rendertype nodes minus the fullscreen exclusions, plus Photon's fullscreen nodes. */
    @Override
    public List<Class<? extends Node>> getSupportNodes() {
        var nodes = new ArrayList<Class<? extends Node>>();
        for (var node : RenderTypeGraph.NODE_REGISTRY.getNodeClasses()) {
            if (!EXCLUDED_NODES.contains(node)) nodes.add(node);
        }
        nodes.addAll(NODE_REGISTRY.getNodeClasses());
        return nodes;
    }

    /**
     * The starter pass: {@code Input} (SAMPLER2D blackboard input) sampled at the fullscreen uv, written
     * straight to the output — a passthrough the author edits into their effect pass.
     */
    @Override
    protected void initializeDefaultEntityShader() {
        if (!(getVertexStageModel() instanceof ContextNodeModel vertexStage)
                || !(getFragmentStageModel() instanceof ContextNodeModel fragmentStage)) {
            return;
        }
        if (!vertexStage.getBlocks().isEmpty() || !fragmentStage.getBlocks().isEmpty()
                || hasNonStageNodes()) {
            return;
        }

        createBlock(vertexStage, FullscreenPositionBlock.class);
        var output = createBlock(fragmentStage, FullscreenOutputBlock.class);

        var inputVar = (VariableDeclarationModelBase) graphModel.createVariable(
                DEFAULT_INPUT, RenderTypeGraphTypes.SAMPLER2D,
                RenderTypeGraphTypes.Sampler2DValue.defaultValue(), VariableKind.INPUT);
        var inputNode = graphModel.createVariableNode(inputVar, new Vector2f(48, -128), null, null);
        var textureSample = createNode(SamplerTexture2DNode.class, 288, -128);

        vertexStage.setPosition(new Vector2f(640, -320));
        fragmentStage.setPosition(new Vector2f(640, -128));

        graphModel.createWire(textureSample.getInputsById().get("sampler"), inputNode.getOutputPort());
        // textureSample.uv is left unconnected — the UV port defaults to the fullscreen uv varying.
        wireDefaultOutput(textureSample, output);
    }

    /**
     * Wire the default network's sampled color into the output block. The builtin sample graphs
     * (e.g. the invert starter) override this to splice their nodes between sample and output.
     */
    protected void wireDefaultOutput(NodeModel textureSample, NodeModel output) {
        graphModel.createWire(output.getInputsById().get("color"), textureSample.getOutputsById().get("color"));
    }

    /** The fixed NDC position block is load-bearing — deleting it would fall back to the matrix
     *  transform path, which has no matrices bound in a fullscreen dispatch. */
    @Override
    public boolean canExecuteCommand(IGraphCommand command) {
        if (command instanceof GraphCommands.DeleteElementsCommand deleteCommand) {
            for (var element : deleteCommand.elementsToDelete) {
                if (element instanceof ICustomNodeModel custom
                        && custom.getNode() instanceof FullscreenPositionBlock) {
                    return false;
                }
            }
        }
        return super.canExecuteCommand(command);
    }

    /** Local mirror of the base class's private block-creation helper (same public model APIs). */
    private NodeModel createBlock(ContextNodeModel contextModel, Class<? extends BlockNode> blockClass) {
        BlockNode userNode;
        try {
            userNode = blockClass.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate block " + blockClass.getName(), e);
        }
        var block = new CustomBlockNodeModelImpl();
        block.setGraphModel(graphModel);
        block.setSpawnFlags(SpawnFlags.DEFAULT);
        block.initCustomNode(userNode);
        block.setContextNodeModel(contextModel);
        block.onCreateNode();
        contextModel.insertBlock(block, -1);
        return block;
    }

    /** Whether the graph already carries any node besides the two fixed stages (loaded graphs do). */
    private boolean hasNonStageNodes() {
        return graphModel.getNodeModels().stream()
                .filter(NodeModel.class::isInstance)
                .map(NodeModel.class::cast)
                .anyMatch(model -> model != getVertexStageModel() && model != getFragmentStageModel());
    }
}
