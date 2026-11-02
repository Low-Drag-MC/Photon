package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Photon's translucent draws in 26.3's improved (order-independent) transparency. A draw joins vanilla's three OIT
 * passes ({@link PhotonFeatureRenderer#executeGroup}) through variants of its own pipeline, built here, when its blend is
 * one OIT can express and its fragment shader writes through an OIT tail ({@code photon:oit.glsl}, a custom shader's
 * twin from {@link CustomShaderAdapter}, a KilaGraph graph's own). Destination-reading blends, depth-test-off and
 * scene-colour materials, editor scenes and shader packs stay on the classic path. Render thread only.
 */
public final class PhotonOit {

    /** How a draw's blend reads the colour its shader writes, for the accumulation. */
    public enum Blend {
        /** (SRC_ALPHA, ONE_MINUS_SRC_ALPHA). */
        ALPHA,
        /** (ONE, ONE_MINUS_SRC_ALPHA): the colour is already weighted by its coverage. */
        PREMULTIPLIED,
        /** (SRC_ALPHA, ONE): adds light weighted by alpha, hides nothing. */
        ADDITIVE,
        /** (ONE, ONE): adds the whole colour. */
        ADDITIVE_ONE
    }

    /** The defines the shaders read, for Photon's tail ({@code photon:oit.glsl}) and KilaGraph's alike. */
    static final String PREMULTIPLIED_DEFINE = "PHOTON_OIT_PREMULTIPLIED";
    static final String ADDITIVE_ONE_DEFINE = "PHOTON_OIT_ADDITIVE_ONE";
    private static final String KG_PREMULTIPLIED_DEFINE = "KG_PREMULTIPLIED_ALPHA";
    private static final String KG_ADDITIVE_ONE_DEFINE = "KG_ADDITIVE_ONE";
    private static final String VANILLA_ADDITIVE_DEFINE = "OIT_ADDITIVE";

    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");
    /** Built once per pipeline; empty when the pipeline cannot take part. Weak: released pipelines drop out. */
    private static final Map<RenderPipeline, Optional<OitPipelineSet>> SETS = new WeakHashMap<>();

    /** Photon drew into the accumulation of the level render under way, and whether one of those views blooms. */
    private static boolean drew;
    private static boolean bloom;
    /** For the UI tests: OIT draws, and whether Photon resolved the sum, this frame and the last. */
    private static int drawsThisFrame;
    private static int drawsLastFrame;
    private static boolean compositedThisFrame;
    private static boolean compositedLastFrame;

    private PhotonOit() {
    }

    /** Whether the world's translucent FX join the OIT passes this frame. */
    public static boolean active() {
        return PhotonConfig.INSTANCE.orderIndependentTransparency.get()
                && Minecraft.getInstance().gameRenderer.useImprovedTransparency()
                && !IrisCompat.isUsingShaderPack();
    }

    /** {@code pipeline}'s blend as the accumulation reads it, or null when OIT cannot express it. */
    @Nullable
    public static Blend blendOf(RenderPipeline pipeline) {
        var states = pipeline.getColorTargetStates();
        if (states.size() != 1 || states.getFirst() == null) {
            return null;
        }
        var function = states.getFirst().blendFunction();
        if (function.isEmpty()) {
            return null;
        }
        // only the colour half: the OIT targets own the coverage
        var color = function.get().color();
        if (color.op() != BlendOp.ADD) {
            return null;
        }
        var src = color.sourceFactor();
        var dst = color.destFactor();
        if (dst == BlendFactor.ONE_MINUS_SRC_ALPHA) {
            if (src == BlendFactor.SRC_ALPHA) return Blend.ALPHA;
            if (src == BlendFactor.ONE) return Blend.PREMULTIPLIED;
        } else if (dst == BlendFactor.ONE) {
            if (src == BlendFactor.SRC_ALPHA) return Blend.ADDITIVE;
            if (src == BlendFactor.ONE) return Blend.ADDITIVE_ONE;
        }
        return null;
    }

    /** Whether {@code pipeline} adds light without hiding anything behind it (additive): no transmittance to add. */
    public static boolean addsLight(RenderPipeline pipeline) {
        var blend = blendOf(pipeline);
        return blend == Blend.ADDITIVE || blend == Blend.ADDITIVE_ONE;
    }

    /** The three compiled OIT variants of {@code base}; null when it can't take part (the draw then stays classic). */
    @Nullable
    public static OitPipelineSet set(RenderPipeline base) {
        var cached = SETS.get(base);
        if (cached == null) {
            cached = Optional.ofNullable(build(base));
            SETS.put(base, cached);
        }
        return cached.orElse(null);
    }

    /** {@code base}'s variant for {@code stage}; null when it takes no part. */
    @Nullable
    public static RenderPipeline variant(RenderPipeline base, OitStage stage) {
        var set = set(base);
        return set == null ? null : set.getPipeline(stage);
    }

    @Nullable
    private static OitPipelineSet build(RenderPipeline base) {
        var blend = blendOf(base);
        var depth = base.getDepthStencilState();
        var fragment = base.getShaders().get(ShaderType.FRAGMENT);
        // what ignores depth draws over everything: after the composite, as vanilla's see-through draws do
        if (blend == null || depth == null || depth.depthTest() == CompareOp.ALWAYS_PASS || fragment == null
                || PhotonPipelines.isWireframe(base) || StackedDistortion.isStacked(base)) {
            return null;
        }
        var twin = PhotonShaderSources.oitTwin(fragment);
        var code = code(twin != null ? twin : fragment);
        if (code == null || !code.contains("photon:oit.glsl") && !code.contains("executeAlphaOnlyPhase")) {
            return null;
        }
        // KilaGraph's premultiplied tail divides the alpha back out, losing light carried at zero coverage
        if (blend == Blend.PREMULTIPLIED && code.contains(KG_PREMULTIPLIED_DEFINE)) {
            return null;
        }
        var built = new OitPipelineSet(
                variant(base, twin, blend, RenderPipelines.OIT_DEPTH_BOUNDS_SNIPPET, "depth_bounds"),
                variant(base, twin, blend, RenderPipelines.OIT_TRANSMITTANCE_SNIPPET, "transmittance"),
                variant(base, twin, blend, RenderPipelines.OIT_ACCUMULATE_SNIPPET, "accumulate"));
        for (var stage : OitStage.values()) {
            if (PhotonPipelines.compiled(built.getPipeline(stage)) == null) {
                release(built);
                return null;
            }
        }
        return built;
    }

    /**
     * {@code base} through vanilla's snippet for one OIT phase, without declaring again a block {@code base} already does
     * (a graph's own layout names Globals and Projection, and a pipeline may not declare a name twice).
     */
    private static RenderPipeline variant(RenderPipeline base, @Nullable Identifier fragment, Blend blend,
                                          RenderPipeline.Snippet phase, String name) {
        var location = base.getLocation();
        // everything but the colour target: the snippet's targets fill the slot, and its depth state replaces ours
        var builder = base.toBuilder()
                .withLocation(Identifier.fromNamespaceAndPath(location.getNamespace(),
                        "pipeline/oit_" + name + "_" + location.getPath().replace('/', '_')))
                .withUnusedColorTargetState(0)
                .withSnippet(withoutDeclared(phase, base));
        if (fragment != null) {
            builder.withFragmentShader(fragment);
        }
        switch (blend) {
            case PREMULTIPLIED -> builder.withShaderDefine(PREMULTIPLIED_DEFINE).withShaderDefine(KG_PREMULTIPLIED_DEFINE);
            case ADDITIVE -> builder.withShaderDefine(VANILLA_ADDITIVE_DEFINE);
            case ADDITIVE_ONE -> builder.withShaderDefine(VANILLA_ADDITIVE_DEFINE)
                    .withShaderDefine(ADDITIVE_ONE_DEFINE).withShaderDefine(KG_ADDITIVE_ONE_DEFINE);
            case ALPHA -> {
            }
        }
        return builder.build();
    }

    /** {@code snippet} without the bindings {@code base} already declares. */
    private static RenderPipeline.Snippet withoutDeclared(RenderPipeline.Snippet snippet, RenderPipeline base) {
        var declared = new HashSet<String>();
        for (var uniform : BindGroupLayout.flattenUniforms(List.copyOf(base.getBindGroupLayouts()))) {
            declared.add(uniform.name());
        }
        var layouts = new ArrayList<BindGroupLayout>();
        for (var layout : snippet.bindGroupLayouts().orElse(List.of())) {
            var builder = BindGroupLayout.builder();
            var any = false;
            for (var uniform : layout.uniforms()) {
                if (declared.contains(uniform.name())) {
                    continue;
                }
                any = true;
                if (uniform.gpuFormat() != null) {
                    builder.withUniform(uniform.name(), uniform.type(), uniform.gpuFormat());
                } else {
                    builder.withUniform(uniform.name(), uniform.type());
                }
            }
            if (any) {
                layouts.add(builder.build());
            }
        }
        return new RenderPipeline.Snippet(snippet.shaders(), snippet.shaderDefines(), Optional.of(List.copyOf(layouts)),
                snippet.colorTargetStates(), snippet.activeColorTargetStateCount(), snippet.depthStencilState(),
                snippet.polygonMode(), snippet.cull(), snippet.vertexFormatPerBuffer(), snippet.vertexFormatMode(),
                snippet.pushConstantSize());
    }

    /** {@code fragment}'s GLSL without its comments, or null. */
    @Nullable
    private static String code(Identifier fragment) {
        var source = PhotonShaderSources.source(fragment, ShaderType.FRAGMENT);
        return source == null ? null : COMMENTS.matcher(source).replaceAll(" ");
    }

    /** Drops what was built for {@code base} (it will never be drawn again). */
    static void release(RenderPipeline base) {
        var cached = SETS.remove(base);
        if (cached != null) {
            cached.ifPresent(PhotonOit::release);
        }
    }

    private static void release(OitPipelineSet set) {
        for (var stage : OitStage.values()) {
            // with a back-faces-first draw's reversed twin
            PhotonPipelines.release(set.getPipeline(stage));
        }
    }

    /** Pipelines rebuilt after a shader reload: what was derived from the old ones goes with them. */
    static void onResourceReload() {
        SETS.clear();
    }

    /** A level render starts its OIT passes. */
    public static void beginLevel() {
        drew = false;
        bloom = false;
    }

    static void noteDrawn(boolean blooms, int draws) {
        drew = true;
        bloom |= blooms;
        drawsThisFrame += draws;
    }

    static void noteComposited() {
        compositedThisFrame = true;
    }

    /** Draws that went through the OIT passes in the last finished frame (UI tests). */
    public static int drawsLastFrame() {
        return drawsLastFrame;
    }

    /** Whether Photon resolved the last finished frame's OIT sum itself, for its bloom (UI tests). */
    public static boolean compositedLastFrame() {
        return compositedLastFrame;
    }

    /** Photon drew into the accumulation of the level render under way. */
    public static boolean drew() {
        return drew;
    }

    static boolean blooms() {
        return bloom;
    }

    static void endFrame() {
        RenderSystem.assertOnRenderThread();
        beginLevel();
        drawsLastFrame = drawsThisFrame;
        drawsThisFrame = 0;
        compositedLastFrame = compositedThisFrame;
        compositedThisFrame = false;
    }
}
