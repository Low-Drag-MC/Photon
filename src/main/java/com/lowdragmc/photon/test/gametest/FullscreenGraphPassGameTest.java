package com.lowdragmc.photon.test.gametest;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.compiler.MaterialUniformLayout;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderGraphCompiler;
import com.lowdragmc.kilagraph.rendertype.nodes.texture.SamplerTexture2DNode;
import com.lowdragmc.kilagraph.test.gametest.KGGameTestHelpers;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.VariableNodeModel;
import com.lowdragmc.photon.client.postfx.runtime.RenderGraphExecutor;
import com.lowdragmc.photon.client.postfx.shadergraph.FullscreenShaderGraph;
import com.lowdragmc.photon.client.postfx.shadergraph.PhotonFullscreenCompiler;
import com.lowdragmc.photon.client.postfx.shadergraph.nodes.TexelSizeNode;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import java.util.HashSet;

import static com.lowdragmc.kilagraph.test.gametest.KGGameTestHelpers.assertFalse;
import static com.lowdragmc.kilagraph.test.gametest.KGGameTestHelpers.assertTrue;

/**
 * A depth texture wired into a fullscreen post-effect graph, end to end on the CPU side: compile the
 * graph, then check the two contracts that decide whether the resulting pass can actually be dispatched.
 *
 * <p><b>Why a depth input is just a sampler.</b> A fullscreen graph never captures the scene itself
 * (the pure-function rule — {@code SceneDepthNode} is excluded); the effect render graph binds
 * {@code SCENE_DEPTH} to one of the graph's {@code SAMPLER2D} input variables, so "wiring depth" is a
 * plain sampler input sampled at the fullscreen uv. That is what these graphs build.</p>
 *
 * <p><b>Contract 1 — every declared uniform is bound.</b> 26.1's {@code GlCommandEncoder.trySetup}
 * validates the pipeline's DECLARED uniforms, not the ones the generated GLSL references, and
 * {@code PhotonFullscreenPass.draw} deliberately binds nothing of Minecraft's. A pass pipeline that
 * declares a block the dispatch doesn't fill throws {@code Missing uniform <name>} on every frame it
 * runs — which is exactly how the {@code DynamicTransforms} regression showed up in the post-effect
 * editor preview.</p>
 *
 * <p><b>Contract 2 — the texel-size companion name.</b> {@code TexelSizeNode} compiles its uniform off
 * the MANGLED sampler identifier ({@code kg_Depth_TexelSize}), and
 * {@code RenderGraphExecutor.dispatchGraph} writes {@code <pass texture key> + _TexelSize}. Those agree
 * only because {@code RenderGraphCompiler} keys a graph pass's textures by
 * {@code variableSamplers().get(displayName)} rather than by the display name. If that ever drifts, the
 * field is silently never written and every blur/downsample kernel reads a zero texel size.</p>
 *
 * <p>No GPU work happens here — compiling a graph and building a {@code RenderPipeline} object are both
 * pure CPU — so this runs on the headless game-test server. The limit that buys: contract 1 compares the
 * declarations against {@link RenderGraphExecutor#BOUND_BUILTIN_UNIFORM}, the executor's statement of what
 * it binds, not against an observed bind. It catches a pipeline growing a new unbindable declaration (a
 * port default pulling in Fog/Projection/Globals, say); it cannot catch the {@code setUniform} call itself
 * being deleted. Verifying that needs a GL context, i.e. running the effect in the editor.</p>
 */
public final class FullscreenGraphPassGameTest {

    private static final String DEPTH_TEXTURE_PASS = "fullscreen_graph_depth_texture_pass";
    private static final String TEXEL_SIZE_COMPANION = "fullscreen_graph_texel_size_companion";

    /**
     * The graph variable that carries the depth texture. This is the starter graph's own
     * {@code SAMPLER2D} input, sampled at the fullscreen uv and written to the output — the effect render
     * graph binds {@code SCENE_DEPTH} to exactly this port ({@code RenderGraphCompiler} keys the pass's
     * textures by {@code variableSamplers().get(name)}), so a depth pass needs no different graph shape.
     */
    private static final String DEPTH = FullscreenShaderGraph.DEFAULT_INPUT;

    private FullscreenGraphPassGameTest() {}

    public static void registerFunctions() {
        PhotonGameTests.registerFunction(DEPTH_TEXTURE_PASS, helper -> {
            // the starter network as-is: depth input -> sample at the fullscreen uv -> output
            var graph = new FullscreenShaderGraph();

            var compiled = graph.createCompiler().compile();
            assertFalse(helper, "the depth pass compiles without stage errors", compiled.hasStageErrors());

            var sampler = compiled.variableSamplers().get(DEPTH);
            assertTrue(helper, "the depth input is declared as a material sampler"
                    + " (variables=" + compiled.variableSamplers() + " samplers=" + compiled.layout().samplers() + ")",
                    sampler != null && compiled.layout().samplers().contains(sampler));
            // Resolution independence: a half-res depth pass must read its input at the same 0..1 uv,
            // which gl_FragCoord/ScreenSize would get wrong.
            assertTrue(helper, "the depth read goes through the fullscreen uv varying",
                    compiled.fragmentSource().contains(PhotonFullscreenCompiler.FS_UV));
            // The pure-function rule: depth arrives through the declared input, never an implicit capture.
            assertFalse(helper, "the pass does not capture the scene depth implicitly", compiled.usesSceneDepth());

            assertEveryPipelineUniformIsBound(helper, compiled);
            assertEveryPipelineSamplerIsBound(helper, compiled);
            helper.succeed();
        });

        PhotonGameTests.registerFunction(TEXEL_SIZE_COMPANION, helper -> {
            // the starter network plus the offset-kernel shape every blur/downsample pass has: read the
            // depth one texel away. The sample node's uv is unconnected in the starter, so this only adds.
            var graph = new FullscreenShaderGraph();
            var depth = depthInputNode(graph);
            var sample = starterSampleNode(graph);
            var texelSize = KGGameTestHelpers.addRegisteredNode(graph, TexelSizeNode.class);
            graph.graphModel.createWire(texelSize.getInputsById().get("sampler"), depth.getOutputPort());
            // UV is wire-compatible with VEC2 (see RenderTypeGraphTypes.UV)
            graph.graphModel.createWire(sample.getInputsById().get("uv"),
                    texelSize.getOutputsById().get("texelSize"));

            var compiled = graph.createCompiler().compile();
            assertFalse(helper, "the texel-size pass compiles without stage errors", compiled.hasStageErrors());

            var sampler = compiled.variableSamplers().get(DEPTH);
            assertTrue(helper, "the depth input is declared as a material sampler"
                    + " (variables=" + compiled.variableSamplers() + " samplers=" + compiled.layout().samplers() + ")",
                    sampler != null);
            // The exact field name dispatchGraph writes for this input, derived the way it derives it.
            var field = sampler + PhotonFullscreenCompiler.TEXEL_SIZE_SUFFIX;
            assertTrue(helper, "the texel-size field the executor writes ('" + field + "') exists",
                    compiled.layout().fields().stream()
                            .anyMatch(f -> f.name().equals(field)));

            assertEveryPipelineUniformIsBound(helper, compiled);
            assertEveryPipelineSamplerIsBound(helper, compiled);
            helper.succeed();
        });
    }

    /** The starter graph's getter node for its {@code SAMPLER2D} input — the pass's depth texture. */
    private static VariableNodeModel depthInputNode(FullscreenShaderGraph graph) {
        return graph.graphModel.getNodeModels().stream()
                .filter(VariableNodeModel.class::isInstance)
                .map(VariableNodeModel.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("starter graph has no input variable node"));
    }

    /** The starter graph's texture sample node (the one reading the depth input). */
    private static NodeModel starterSampleNode(FullscreenShaderGraph graph) {
        return graph.graphModel.getNodeModels().stream()
                .filter(NodeModel.class::isInstance)
                .map(NodeModel.class::cast)
                .filter(model -> model instanceof ICustomNodeModel custom
                        && custom.getNode() instanceof SamplerTexture2DNode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("starter graph has no sample node"));
    }

    /**
     * The regression guard: every uniform the pass pipeline declares must be one the dispatch binds —
     * {@code DynamicTransforms} from {@code dispatchGraph}, the material UBO and the graph's KG-managed
     * blocks from {@code bindCustomUniforms}. Nothing else has a binder.
     */
    private static void assertEveryPipelineUniformIsBound(GameTestHelper helper, CompiledShaderGraph compiled) {
        var bound = new HashSet<String>();
        bound.add(RenderGraphExecutor.BOUND_BUILTIN_UNIFORM);
        bound.add(MaterialUniformLayout.UBO_NAME);
        compiled.uniformBlocks().forEach(block -> bound.add(block.uboName()));
        for (var uniform : PhotonPipelines.fullscreenGraph(compiled).getUniforms()) {
            assertTrue(helper, "pipeline uniform '" + uniform.name() + "' is bound at dispatch",
                    bound.contains(uniform.name()));
        }
    }

    /**
     * The same contract for samplers, which {@code trySetup} validates just as strictly.
     * {@code bindCustomUniforms} binds every material sampler, plus KilaGraph's own scene samplers when
     * the graph flagged them — which a fullscreen graph never should, its scene nodes being excluded.
     */
    private static void assertEveryPipelineSamplerIsBound(GameTestHelper helper, CompiledShaderGraph compiled) {
        var bound = new HashSet<>(compiled.layout().samplers());
        if (compiled.usesSceneColor()) bound.add(ShaderGraphCompiler.SCENE_COLOR_SAMPLER);
        if (compiled.usesSceneDepth()) bound.add(ShaderGraphCompiler.SCENE_DEPTH_SAMPLER);
        for (var sampler : PhotonPipelines.fullscreenGraph(compiled).getSamplers()) {
            assertTrue(helper, "pipeline sampler '" + sampler + "' is bound at dispatch",
                    bound.contains(sampler));
        }
    }

    public static void register(RegisterGameTestsEvent event,
                                Holder<TestEnvironmentDefinition<?>> environment) {
        PhotonGameTests.registerFunctionTest(event, DEPTH_TEXTURE_PASS,
                PhotonGameTests.defaultTestData(environment));
        PhotonGameTests.registerFunctionTest(event, TEXEL_SIZE_COMPANION,
                PhotonGameTests.defaultTestData(environment));
    }
}
