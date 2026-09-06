package com.lowdragmc.photon.uitest;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.nodes.math.basic.MultiplyNode;
import com.lowdragmc.kilagraph.rendertype.nodes.math.vector.TransformNode;
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
 * Does <b>object space</b> actually mean the mesh's own local space on the GPU-instanced paths?
 *
 * <p><b>The bug.</b> Photon has no per-draw model matrix: {@code getParticleData()} yields camera-relative
 * WORLD vertices, so this pipeline's {@code ModelViewMat} is the VIEW matrix and the real object&rarr;world is
 * the per-instance GPU expansion ({@code quatToMat(iRot)} / {@code iScale} / {@code iPos} / {@code iSize}).
 * {@code PhotonShaderCompiler} has always reported that through the position/normal seams, so the Position
 * and Normal nodes' "Object" outputs were right. But the <b>Transform</b> and <b>View Direction</b> nodes
 * read {@code ModelViewMat}/{@code IModelViewMat} directly, which collapses to the view matrix here:
 * {@code object → world} degenerated to "input + cameraPos" (treating a mesh-local vertex as a world one) and
 * {@code clip → object} could only ever hand back camera-relative world. So
 * {@code Position(Object) → Transform(object → clip)} did not reproduce the particle's own clip position.
 * KilaGraph now routes that leg through overridable {@code objectToViewMatrix()}/{@code viewToObjectMatrix()}
 * seams, which {@code PhotonShaderCompiler} fills from {@code particle.glsl}'s new
 * {@code photon_objectToWorld()} / {@code photon_worldToObject()}.
 *
 * <p>Two things are hard to check any other way, mirroring {@code TangentScenario}:
 *
 * <p><b>The new GLSL compiles.</b> The two matrix builders live inside {@code particle.glsl}'s {@code #ifdef}
 * chain and are compiled once per render-path variant. Only a real driver can say whether each branch is
 * valid GLSL; nothing else in the build ever compiles them, so a mistake would surface as an in-game render
 * failure on whichever emitter happened to use that path. (The KilaGraph-side GameTests cover the seam
 * mechanism and the generated expressions, but they never touch a driver.)
 *
 * <p><b>The per-instance matrix actually reaches the graph.</b> If the seam override stopped being reached,
 * graphs would keep compiling and rendering — just against the view matrix, with the particle's own
 * rotate/scale/translate silently dropped. That is invisible without inspecting the generated GLSL.
 */
@LDLRegisterClient(name = "object_space", group = "photon", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ObjectSpaceScenario implements UIScenario {

    /** The vertex-stage builders {@code particle.glsl} exposes, and the varyings that carry them into the
     *  fragment stage (deliberately different names — GLSL puts functions and globals in one namespace, so a
     *  varying named after its function would be a redefinition error in the vsh). */
    private static final String O2W_FN = "photon_objectToWorld()";
    private static final String W2O_FN = "photon_worldToObject()";
    private static final String O2W_VARYING = "photon_o2w";
    private static final String W2O_VARYING = "photon_w2o";

    @Override
    public void define(ScenarioBuilder s) {
        s.step("every particle.glsl variant still compiles with the object<->world matrices",
                ParticleShaderVariants::checkAll)

        .step("object -> world folds in the per-instance matrix", ctx -> {
            var compiled = compileTransform(ctx, "object", "world", "position");
            if (compiled == null) {
                return;
            }
            ctx.check("the graph compiled without stage errors", !compiled.hasStageErrors(),
                    "no errors", compiled.stageErrors().toString());
            var vsh = compiled.vertexSource();
            var fsh = compiled.fragmentSource();

            // The override's fingerprint: the object leg is ModelViewMat COMPOSED WITH the instance matrix,
            // not ModelViewMat alone (which on this pipeline is the view matrix).
            ctx.check("the object leg reads the per-instance object->world",
                    fsh.contains(O2W_VARYING), O2W_VARYING + " present", trace(vsh + "\n" + fsh));
            // particle.glsl is a VERTEX-stage include, so the fragment stage cannot call the builder — the
            // matrix has to ride a varying, and a missing assignment links fine and reads garbage.
            ctx.check("the vsh declares the matrix varying", vsh.contains("out mat4 " + O2W_VARYING + ";"),
                    "declared", "missing");
            ctx.check("the vsh assigns it from " + O2W_FN,
                    vsh.contains(O2W_VARYING + " = " + O2W_FN + ";"), "assigned", "never assigned");
            ctx.check("the fsh reads the interpolated matrix", fsh.contains("in mat4 " + O2W_VARYING + ";"),
                    "declared", "missing");
        })

        .step("clip -> object uses the INVERSE per-instance matrix", ctx -> {
            var compiled = compileTransform(ctx, "clip", "object", "position");
            if (compiled == null) {
                return;
            }
            ctx.check("the graph compiled without stage errors", !compiled.hasStageErrors(),
                    "no errors", compiled.stageErrors().toString());
            var fsh = compiled.fragmentSource();
            // Reconstructing a mesh-local coordinate from a depth/NDC sample is the whole point of this
            // direction, and it needs world->object — not the forward matrix again.
            ctx.check("the reverse leg reads the per-instance world->object",
                    fsh.contains(W2O_VARYING), W2O_VARYING + " present",
                    trace(compiled.vertexSource() + "\n" + fsh));
            ctx.check("the reverse leg does not reuse the forward matrix",
                    !fsh.contains(O2W_VARYING), "forward matrix absent", O2W_VARYING + " present");
        })

        .step("type=normal transforms by the inverse-transpose", ctx -> {
            var compiled = compileTransform(ctx, "object", "world", "normal");
            if (compiled == null) {
                return;
            }
            var fsh = compiled.fragmentSource();
            // Direction and normal share a matrix only while it is a pure rotation. The seam deliberately
            // puts a SCALE in the object leg here (per-instance iScale; a billboard's iSize is non-uniform
            // almost always), under which M*n is not perpendicular to the transformed surface. The
            // inverse-transpose of one seam is the transpose of the other, so no inverse() is needed.
            ctx.check("a normal transposes the inverse matrix",
                    fsh.contains("transpose(mat3((" + W2O_VARYING), "transposed inverse", trace(fsh));
            ctx.check("a normal does not reuse the forward object matrix",
                    !fsh.contains("ModelViewMat * " + O2W_VARYING), "forward matrix absent", "forward matrix used");
            ctx.check("no per-pixel inverse() is emitted", !fsh.contains("inverse("),
                    "no inverse()", "inverse() present");
        })

        .step("a graph that converts no spaces pays nothing", ctx -> {
            // The matrices are lazily-declared varyings plus vsh-only functions: a mat4 varying costs 4
            // interpolator slots and the builders cost a matrix (and an inverse) per vertex, so the default
            // graph must not carry them at all.
            var compiled = new PhotonShaderCompiler(new ShaderGraph()).compile();
            var glsl = compiled.vertexSource() + "\n" + compiled.fragmentSource();
            for (var name : List.of(O2W_VARYING, W2O_VARYING, O2W_FN, W2O_FN)) {
                ctx.check("the default graph does not reference " + name, !glsl.contains(name),
                        "absent", "present");
            }
        });
    }

    /**
     * A ShaderGraph whose fragment chain runs one Transform node. The default graph multiplies the texture
     * sample by the vertex colour on its way to base colour, so re-wiring that multiply's second input is the
     * shortest way to pull the conversion into the compile (same trick as {@code TangentScenario}).
     */
    private static CompiledShaderGraph compileTransform(TestContext ctx, String from, String to, String type) {
        var graph = new ShaderGraph();
        var multiply = findMultiply(graph);
        ctx.check("the default graph has a Multiply node to re-wire", multiply != null, "found", "missing");
        if (multiply == null) {
            return null;
        }
        // addRegisteredNode, not addNode: the latter spawns an ORPHAN, which never joins graphModel's node
        // list and so is not walked when the compiler traverses back from the master — the node would be
        // wired and still contribute nothing.
        var transform = KGGameTestHelpers.addRegisteredNode(graph, TransformNode.class);
        KGGameTestHelpers.setOption(transform, "from", from);
        KGGameTestHelpers.setOption(transform, "to", to);
        KGGameTestHelpers.setOption(transform, "type", type);
        var dst = multiply.getInputsById().get("b");
        var src = transform.getOutputsById().get("out");
        ctx.check("both ports resolved for the re-wire", dst != null && src != null,
                "multiply.b + transform.out", "dst=" + dst + " src=" + src);
        if (dst == null || src == null) {
            return null;
        }
        // createWire only reuses an identical pair, it does not REPLACE — leaving the default graph's own
        // wire in place and the compiler still reading that one. Free the input first.
        graph.graphModel.deleteWires(List.copyOf(graph.graphModel.getWiresForPort(dst)));
        KGGameTestHelpers.wire(graph, dst, src);
        return new PhotonShaderCompiler(graph).compile();
    }

    /** What the generated GLSL says about object space, so a failure names the cause instead of just
     *  "absent" — the interesting case is the seam silently falling back to the bare view matrix. */
    private static String trace(String glsl) {
        var seen = new ArrayList<String>();
        for (var marker : List.of(O2W_VARYING, W2O_VARYING, O2W_FN, W2O_FN,
                "ModelViewMat", "kg_IModelViewMat", "kg_pd.ObjectPosition")) {
            if (glsl.contains(marker)) seen.add(marker);
        }
        return seen.isEmpty() ? "no space-conversion code emitted at all (the Transform node never reached the compile)"
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
