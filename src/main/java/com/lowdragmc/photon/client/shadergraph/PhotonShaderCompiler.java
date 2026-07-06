package com.lowdragmc.photon.client.shadergraph;

import com.lowdragmc.kilagraph.rendertype.compiler.GlslType;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderExpr;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderGraphCompiler;
import com.lowdragmc.kilagraph.rendertype.format.KGVertexElement;
import com.lowdragmc.kilagraph.rendertype.format.KGVertexElements;

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

    public PhotonShaderCompiler(ShaderGraph graph) {
        super(graph);
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

    /** Particles carry no chunk {@code ModelOffset}; the position is the camera-relative world position. */
    @Override
    protected ShaderExpr modelPosition() {
        return new ShaderExpr(PARTICLE_DATA + ".Position", GlslType.VEC3);
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
