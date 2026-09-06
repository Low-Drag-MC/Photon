package com.lowdragmc.photon.uitest;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.nodes.input.TangentNode;
import com.lowdragmc.kilagraph.rendertype.nodes.math.basic.MultiplyNode;
import com.lowdragmc.kilagraph.test.gametest.KGGameTestHelpers;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.ICustomNodeModel;
import com.lowdragmc.lowdraglib2.nodegraphtookit.model.node.NodeModel;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.lowdragmc.photon.client.shadergraph.ShaderGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Does tangent support actually work? Two things are hard to check any other way.
 *
 * <p>Loading a model that carries tangents is {@code GltfScenario}'s job, not this one's.
 *
 * <p><b>The GLSL compiles.</b> {@code particle.glsl} is a multi-way {@code #ifdef} chain and the tangent
 * added a branch ({@code PHOTON_TANGENT}) that repacks the model path's attribute layout. Only a real
 * driver can say whether each branch is valid GLSL, and nothing else in the build ever compiles them — a
 * broken variant would surface as an in-game render failure on whichever emitter happened to use it. See
 * {@link ParticleShaderVariants}.
 *
 * <p><b>The tangent reaches the shader graph.</b> KilaGraph derives a tangent basis because Minecraft has
 * no tangent attribute; Photon overrides that seam ({@code PhotonShaderCompiler#tangentBasis}) to read the
 * uploaded {@code ParticleData.Tangent} instead. If the override stopped being reached, graphs would keep
 * compiling and rendering — just off a screen-space-derivative frame rather than the mesh's own tangent.
 * That is invisible without inspecting the generated GLSL, which is exactly what the second step does.
 */
@LDLRegisterClient(name = "tangent", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class TangentScenario implements UIScenario {

    @Override
    public void define(ScenarioBuilder s) {
        s.step("every particle.glsl variant compiles on the driver", ParticleShaderVariants::checkAll)

        .step("a graph reading the tangent reads it from ParticleData", ctx -> {
            var compiled = compileGraphReadingTangent(ctx);
            if (compiled == null) {
                return;
            }
            ctx.check("the graph compiled without stage errors", !compiled.hasStageErrors(),
                    "no errors", compiled.stageErrors().toString());

            var glsl = compiled.vertexSource() + "\n" + compiled.fragmentSource();
            // The override's fingerprint: the basis is read off ParticleData rather than derived.
            ctx.check("the tangent comes from ParticleData.Tangent", glsl.contains("kg_pd.Tangent"),
                    "kg_pd.Tangent present", tangentTrace(glsl));
            // KilaGraph's fallback tiers. Their absence is what proves tier 1 (the real attribute) won.
            ctx.check("no screen-space cotangent frame was emitted", !glsl.contains("dFdx"),
                    "no dFdx", "dFdx present");
            ctx.check("KilaGraph's own basis-from-normal tier was not used",
                    !glsl.contains("kg_tangentFrameFromNormal"),
                    "no kg_tangentFrameFromNormal", "kg_tangentFrameFromNormal present");
        });
    }

    /**
     * A ShaderGraph whose fragment chain reads the tangent. The default graph multiplies the texture
     * sample by the vertex colour on its way to base colour, so re-wiring that multiply's second input to
     * a Tangent node is the shortest way to pull the tangent basis into the compile.
     */
    private static CompiledShaderGraph compileGraphReadingTangent(TestContext ctx) {
        var graph = new ShaderGraph();
        var multiply = findMultiply(graph);
        ctx.check("the default graph has a Multiply node to re-wire", multiply != null, "found", "missing");
        if (multiply == null) {
            return null;
        }
        // addRegisteredNode, not addNode: the latter spawns an ORPHAN, which never joins graphModel's node
        // list and so is not walked when the compiler traverses back from the master — the node would be
        // wired and still contribute nothing.
        var tangent = KGGameTestHelpers.addRegisteredNode(graph, TangentNode.class);
        // TangentNode defaults to world space — the space a normal map is consumed in.
        var dst = multiply.getInputsById().get("b");
        var src = tangent.getOutputsById().get("out");
        ctx.check("both ports resolved for the re-wire", dst != null && src != null,
                "multiply.b + tangent.out", "dst=" + dst + " src=" + src);
        if (dst == null || src == null) {
            return null;
        }
        // createWire only reuses an identical pair, it does not REPLACE — leaving the default graph's own
        // wire in place and the compiler still reading that one. Free the input first.
        graph.graphModel.deleteWires(List.copyOf(graph.graphModel.getWiresForPort(dst)));
        KGGameTestHelpers.wire(graph, dst, src);
        var wires = graph.graphModel.getWiresForPort(dst);
        ctx.check("the tangent node is the multiply's only b input",
                wires.size() == 1 && wires.getFirst().getFromPort() == src,
                "1 wire from tangent.out", wires.size() + " wire(s)");
        return new PhotonShaderCompiler(graph).compile();
    }

    /** What the generated GLSL says about tangents, so a failure names the cause instead of just "absent". */
    private static String tangentTrace(String glsl) {
        var seen = new ArrayList<String>();
        for (var marker : List.of("kg_pd.Tangent", "photon_worldTangent", "photon_objectTangent",
                "kg_objectTangent", "dFdx", "kg_tangentFrameFromNormal", "PHOTON_NO_TANGENT")) {
            if (glsl.contains(marker)) seen.add(marker);
        }
        return seen.isEmpty() ? "no tangent code emitted at all (the Tangent node never reached the compile)"
                : "found instead: " + String.join(", ", seen);
    }

    /** The first Multiply node of the default graph's fragment chain (texture sample x vertex colour). */
    private static NodeModel findMultiply(ShaderGraph graph) {
        return graph.graphModel.getNodeModels().stream()
                .filter(NodeModel.class::isInstance)
                .map(NodeModel.class::cast)
                .filter(node -> node instanceof ICustomNodeModel custom
                        && custom.getNode() instanceof MultiplyNode)
                .findFirst()
                .orElse(null);
    }
}
