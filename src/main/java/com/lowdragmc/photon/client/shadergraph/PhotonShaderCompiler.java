package com.lowdragmc.photon.client.shadergraph;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderGraphCompiler;
import com.lowdragmc.kilagraph.rendertype.compiler.TangentBasis;
import com.lowdragmc.kilagraph.rendertype.format.KGVertexElement;
import com.lowdragmc.kilagraph.rendertype.format.KGVertexElements;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The Photon compile target: identical node semantics to KilaGraph's compiler, but the vertex stage reads
 * its inputs from {@code photon:particle.glsl}'s {@code ParticleData} instead of raw {@code in} attributes.
 * That include declares the attribute layout for all three Photon paths — plain {@code BLOCK} attributes
 * (CPU quads / trails / beams), {@code PARTICLE_INSTANCE} (billboard instancing) and
 * {@code PARTICLE_MODEL_INSTANCE} (model instancing) — selected by {@code #define}s injected at shader
 * build, so ONE compiled source pair serves every path.
 *
 * <p>Scene color/depth read Photon's pipeline capture ({@code SamplerSceneColor}/{@code SamplerSceneDepth},
 * bound from {@code RenderPassPipeline}'s scene sampler — Iris-compatible) instead of KilaGraph's
 * {@code SceneCaptureManager}. The screen UV is window-relative {@code gl_FragCoord.xy / ScreenSize}
 * (see {@link #screenUv()}) — the immediate editor scene renders into a sub-viewport of the window-sized
 * capture, so window-relative sampling is correct there and in-world alike.</p>
 */
public class PhotonShaderCompiler extends ShaderGraphCompiler {

    /** The vertex-stage {@code ParticleData} local every attribute read resolves through. */
    private static final String PARTICLE_DATA = "kg_pd";

    /** Engine-driven sampler names, bound by ShaderGraphMaterial from the render pipeline's scene sampler. */
    public static final String SCENE_COLOR = "SamplerSceneColor";
    public static final String SCENE_DEPTH = "SamplerSceneDepth";
    /** Engine-driven viewport uniform (x, y, width, height), bound by ShaderGraphMaterial. */
    public static final String VIEWPORT = "U_ViewPort";

    /** The compiler currently running {@link #compile()} (render thread only) — lets nodes without
     *  compiler access (e.g. AdditionalDataNode) report metadata like used data channels. */
    @Nullable
    private static PhotonShaderCompiler CURRENT;

    /** {@link PhotonGpuChannels} bits of every additional-data channel the graph reads. */
    @Getter
    private long usedChannelMask;

    /** Whether the graph reads any user custom-data stream (a {@code CustomDataNode}). */
    @Getter
    private boolean usesCustomData;

    public PhotonShaderCompiler(ShaderGraph graph) {
        super(graph);
    }

    @Nullable
    public static PhotonShaderCompiler current() {
        return CURRENT;
    }

    public void markChannelUsed(PhotonGpuChannels.Channel channel) {
        usedChannelMask |= channel.bit();
    }

    public void markCustomDataUsed() {
        usesCustomData = true;
    }

    @Override
    public CompiledShaderGraph compile() {
        var previous = CURRENT;
        CURRENT = this;
        try {
            return super.compile();
        } finally {
            CURRENT = previous;
        }
    }

    @Override
    protected String vertexInputsBlock() {
        // particle.glsl declares the per-define attribute layouts and getParticleData().
        return "#moj_import <photon:particle.glsl>\n";
    }

    @Override
    protected String vertexPrologue() {
        return "    ParticleData " + PARTICLE_DATA + " = getParticleData();\n";
    }

    /** Route every raw attribute read through the {@code ParticleData} struct. */
    @Override
    protected String attributeRef(KGVertexElement element) {
        if (element == KGVertexElements.POSITION) return PARTICLE_DATA + ".Position";
        if (element == KGVertexElements.COLOR) return PARTICLE_DATA + ".Color";
        if (element == KGVertexElements.UV0) return PARTICLE_DATA + ".UV";
        if (element == KGVertexElements.UV2) return PARTICLE_DATA + ".LightUV";
        if (element == KGVertexElements.NORMAL) return PARTICLE_DATA + ".Normal";
        return super.attributeRef(element);
    }

    // modelPosition() is deliberately NOT overridden: the base composes it from attributeRef(POSITION)
    // (= kg_pd.Position, our single input seam) + ModelOffset — which KGBuiltinUniforms binds to 0 for
    // particles (they carry no chunk offset) — AND it honors a driven VertexModelPositionBlock's displaced
    // position. So the whole vertex stage (gl_Position, the kg_modelPos varying, fog distances, view dir)
    // follows vertex animation uniformly. Overriding it to return kg_pd.Position directly silently dropped
    // that displacement, making world-position-offset / vsh position animation a no-op.

    // ---- coordinate-space seams --------------------------------------------------------------
    // Photon's vertices arrive already in (camera-relative) WORLD space via getParticleData(), and the
    // object->world transform lives in that GPU expansion (rotMat/iScale/iPos), NOT in a matrix. So WORLD is
    // the primary space and OBJECT is a SEPARATE source (ParticleData.ObjectPosition/ObjectNormal) — neither
    // is derived from the other by a matrix; view derives from world. worldSpaceNormal is inherited: the
    // base's mat3(IViewMat·ModelViewMat)·objectNormal round-trips an already-world normal back to world and
    // honors a driven VertexModelNormalBlock.

    /** Object/model space (model instancing = mesh-local; billboards = centered quad coord; else world),
     *  from {@code ParticleData.ObjectPosition}. */
    @Override
    protected ShaderExpr objectSpacePosition() {
        return varyingInput("photon_objectPos", GlslType.VEC3,
                () -> new ShaderExpr(PARTICLE_DATA + ".ObjectPosition", GlslType.VEC3),
                new ShaderExpr("vPos", GlslType.VEC3));
    }

    /** Absolute world = camera-relative world ({@code kg_pd.Position} via {@code meshPosition()}, honoring vsh
     *  displacement) + the camera world position. */
    @Override
    protected ShaderExpr worldSpacePosition() {
        return new ShaderExpr("(" + meshPosition().code() + " + " + cameraWorldPos().code() + ")", GlslType.VEC3);
    }

    /** Eye/view space: {@code ModelViewMat · <camera-relative world>} (ModelViewMat is world→view for particles). */
    @Override
    protected ShaderExpr viewSpacePosition() {
        String mv = useBuiltinUniform("ModelViewMat", GlslType.MAT4);
        return new ShaderExpr("(" + mv + " * vec4(" + meshPosition().code() + ", 1.0)).xyz", GlslType.VEC3);
    }

    /** Object/model-space normal (model instancing = mesh-local {@code aNormal}; else world), from
     *  {@code ParticleData.ObjectNormal}, normalized. */
    @Override
    protected ShaderExpr objectSpaceNormal() {
        ShaderExpr n = varyingInput("photon_objectNormal", GlslType.VEC3,
                () -> new ShaderExpr(PARTICLE_DATA + ".ObjectNormal", GlslType.VEC3),
                new ShaderExpr("vNormal", GlslType.VEC3));
        return new ShaderExpr("normalize(" + n.code() + ")", GlslType.VEC3);
    }

    // ---- tangent basis seam ------------------------------------------------------------------
    // KilaGraph derives its basis in OBJECT space and rotates it into view/world, because Minecraft
    // carries no tangent attribute. ParticleData always has one: the uploaded aTangent for model
    // instancing, and an exact closed-form dP/du on the paths whose geometry the vertex shader builds
    // itself (billboards, trails, beams). Like the position/normal seams above, Photon's object->world
    // transform is the per-instance GPU expansion inside getParticleData(), NOT a matrix.
    // The base's mat3(IViewMat * ModelViewMat) rotation is the identity for particles, so inheriting it
    // would hand back a mesh-local frame labelled "world" with the particle's own rotation silently
    // dropped. Read each space from its own ParticleData source instead.

    /** Per-space, per-stage basis memo. The base's own map lives on its private stage scope, and the
     *  columns are hoisted temps — reusing a vertex-stage temp in the fragment stage would not compile,
     *  hence the stage in the key. */
    private final Map<String, TangentBasis> tangentBases = new HashMap<>();

    @Override
    protected TangentBasis tangentBasis(String space) {
        // A per-node preview has no vertex stage (so no ParticleData at all); the base's uv cotangent
        // frame off the preview quad is exactly right there.
        if (isPreview()) {
            return super.tangentBasis(space);
        }
        var key = space + (isFragmentStage() ? "|f" : "|v");
        var cached = tangentBases.get(key);
        if (cached != null) {
            return cached;
        }
        var basis = buildBasis(space);
        tangentBases.put(key, basis);
        return basis;
    }

    /**
     * The basis for one space. N comes from the normal seam for that space so tangent and normal always
     * agree; T from {@code ParticleData}'s matching tangent. The Gram-Schmidt + {@code sign(w)} mirror
     * KilaGraph's own per-vertex-tangent tier: interpolation across a triangle leaves T neither unit
     * length nor perpendicular to N, and w must not scale B if it drifts off ±1 in the varying.
     */
    private TangentBasis buildBasis(String space) {
        var n = hoist(GlslType.VEC3, switch (space) {
            case "object" -> objectSpaceNormal().code();
            case "view" -> viewSpaceNormal().code();
            default -> worldSpaceNormal().code();
        });
        // object reads the mesh-local tangent; world/view read the already-rotated one (view is world
        // turned by ModelViewMat, the same rotation viewSpaceNormal uses).
        var tangent4 = "object".equals(space) ? objectTangent() : worldTangent();
        var raw = "view".equals(space)
                ? "(mat3(" + useBuiltinUniform("ModelViewMat", GlslType.MAT4) + ") * " + tangent4.code() + ".xyz)"
                : tangent4.code() + ".xyz";
        var t3 = hoist(GlslType.VEC3, raw);
        var proj = hoist(GlslType.VEC3, t3.code() + " - " + n.code()
                + " * dot(" + n.code() + ", " + t3.code() + ")");

        // Gram-Schmidt can collapse: whenever the tangent ends up parallel to the normal the projection is
        // zero and normalize() would hand back NaN for the whole frame. That is not hypothetical — the two
        // paths with nothing to report (the CPU path, and model instancing with the Tangent setting off)
        // hand back the constant PHOTON_NO_TANGENT (+X), so every surface facing along world X hits it.
        // Fall back to the same branchless Duff frame KilaGraph's own basis-from-normal tier uses
        // (TangentGlsl#FROM_NORMAL, inlined because addFunction is not visible here): arbitrary around the
        // normal, but orthonormal and finite.
        var s = hoist(GlslType.FLOAT, "(" + n.code() + ".z >= 0.0 ? 1.0 : -1.0)");
        var a = hoist(GlslType.FLOAT, "(-1.0 / (" + s.code() + " + " + n.code() + ".z))");
        var fallback = hoist(GlslType.VEC3, "vec3("
                + "1.0 + " + s.code() + " * " + n.code() + ".x * " + n.code() + ".x * " + a.code() + ", "
                + s.code() + " * (" + n.code() + ".x * " + n.code() + ".y * " + a.code() + "), "
                + "-" + s.code() + " * " + n.code() + ".x)");

        var usable = "dot(" + proj.code() + ", " + proj.code() + ") > 1.0e-12";
        var t = hoist(GlslType.VEC3,
                "(" + usable + " ? normalize(" + proj.code() + ") : " + fallback.code() + ")");
        // the fallback frame has no uv to be handed by, so it is simply right-handed
        var b = hoist(GlslType.VEC3, "(cross(" + n.code() + ", " + t.code() + ") * ("
                + usable + " ? sign(" + tangent4.code() + ".w) : 1.0))");
        return new TangentBasis(t, b, n);
    }

    /** {@code ParticleData.Tangent} — already camera-relative world, like {@code ParticleData.Normal}. */
    private ShaderExpr worldTangent() {
        return varyingInput("photon_worldTangent", GlslType.VEC4,
                () -> new ShaderExpr(PARTICLE_DATA + ".Tangent", GlslType.VEC4),
                new ShaderExpr("vec4(1.0, 0.0, 0.0, 1.0)", GlslType.VEC4));
    }

    /** The mesh-local tangent, mirroring {@link #objectSpaceNormal()}. {@code ParticleData} carries it as
     *  a vec4 so the handedness comes along without reaching into the world tangent. */
    private ShaderExpr objectTangent() {
        return varyingInput("photon_objectTangent", GlslType.VEC4,
                () -> new ShaderExpr(PARTICLE_DATA + ".ObjectTangent", GlslType.VEC4),
                new ShaderExpr("vec4(1.0, 0.0, 0.0, 1.0)", GlslType.VEC4));
    }

    /** Eye/view-space normal: the world normal rotated world→view. */
    @Override
    protected ShaderExpr viewSpaceNormal() {
        String mv = useBuiltinUniform("ModelViewMat", GlslType.MAT4);
        return new ShaderExpr("normalize(mat3(" + mv + ") * " + worldSpaceNormal().code() + ")", GlslType.VEC3);
    }

    /**
     * Real compiles read Photon's pipeline capture; <b>preview</b> compiles fall back to KilaGraph's
     * {@code KG_SceneColor}/{@code KG_SceneDepth} (bound from {@code SceneCaptureManager} by the preview
     * material) — the editor has no Photon render pass, so this is what lets the Scene Color/Depth nodes
     * show the captured world behind the editor instead of black. (No world rendering = still black.)
     */
    @Override
    protected String sceneColorSamplerName() {
        return (isPreview() || isEditorPreview()) ? super.sceneColorSamplerName() : SCENE_COLOR;
    }

    @Override
    protected String sceneDepthSamplerName() {
        return (isPreview() || isEditorPreview()) ? super.sceneDepthSamplerName() : SCENE_DEPTH;
    }
}
