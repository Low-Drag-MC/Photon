package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeGraphMaterial;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The render-layer index the drain reads to draw Photon geometry manually: RenderType →
 * {@link PhotonDrawInfo}. It also holds the build/drop primitives for the PER-MATERIAL-OWNED custom
 * shaders (those RenderTypes are NOT cached here — {@code CustomShaderMaterial} owns them). The
 * shared, value-based factory for the standard HDR materials lives in the material layer, in
 * {@code MaterialRenderTypes}.
 */
public final class PhotonRenderTypes {

    /** What Photon needs to draw a render type itself; render types without one are not Photon's. */
    public record PhotonDrawInfo(Programs programs, Bindings bindings,
                                 @Nullable InstancedRecipe instanced) {

        public record Programs(RenderPipeline main) {
        }

        /**
         * Everything a pass binds for this material; none of it comes from the RenderSetup.
         * {@code legacyDepth}: a custom shader written for forward-Z depth.
         */
        public record Bindings(Map<String, Identifier> textures,
                               List<String> sceneSamplers,
                               @Nullable PhotonCustomUniforms customUniforms,
                               @Nullable GraphSource graph,
                               boolean legacyDepth) {
            public Bindings(Map<String, Identifier> textures, List<String> sceneSamplers,
                            @Nullable PhotonCustomUniforms customUniforms, @Nullable GraphSource graph) {
                this(textures, sceneSamplers, customUniforms, graph, false);
            }
        }

        /**
         * The recipe for re-deriving this material's pipeline against a GPU-instanced vertex stage —
         * the instanced variants are separate pipelines, not a bind-time choice (26.1 bakes the
         * {@code #define} and the vertex format into the pipeline). {@code null} = the material has no
         * instanced form and its emitter must take the CPU path.
         * <p>
         * The three sources are mutually exclusive and checked in this order by
         * {@code Emitter.bakeInstancedGroup}: a shader graph (read from {@link Bindings#graph}),
         * a custom user shader ({@link #customShaderKey}), else Photon's own stage
         * ({@link #hdrFragment}).
         */
        public record InstancedRecipe(PhotonPipelines.ParticlePipelineKey key,
                                      @Nullable Identifier hdrFragment,
                                      @Nullable PhotonPipelines.CustomShaderKey customShaderKey) {
        }
    }

    /**
     * A KilaGraph shader-graph material drawn through Photon's own pipeline (the 1.21 split: KG generates
     * the GLSL, Photon owns the draw). {@code compiled} is what {@link PhotonPipelines#graphShader} needs to
     * build the instanced/bloom variants; {@code material} owns the values — its {@code prepareUniforms} runs
     * before the pass and {@code bindCustomUniforms} inside it, since KilaGraph's own draw hook never fires
     * for a pass Photon opened.
     * <p>
     * {@code usedChannelMask}/{@code usesCustomData} are the graph's additional-GPU-data demands: they decide
     * whether the instanced pipeline declares {@code PhotonData}/{@code PhotonCustomData}, and the emitter
     * unions them across the pass's materials to decide what to upload.
     */
    public record GraphSource(CompiledShaderGraph compiled,
                              RenderTypeGraphMaterial material,
                              long usedChannelMask, boolean usesCustomData) {
    }

    private static final Map<RenderType, PhotonDrawInfo> DRAW_INFO = new ConcurrentHashMap<>();

    @Nullable
    public static PhotonDrawInfo drawInfo(RenderType renderType) {
        return DRAW_INFO.get(renderType);
    }

    private PhotonRenderTypes() {
    }

    /** Register the DrawInfo for a freshly built RenderType. Used by {@code MaterialRenderTypes} for
     *  the shared HDR types; custom shaders register through {@link #createCustomShader}. */
    public static void registerDrawInfo(RenderType renderType, PhotonDrawInfo info) {
        DRAW_INFO.put(renderType, info);
    }

    // ---- custom user shaders --------------------------------------------------------------------
    //
    // These are NOT cached here. Each CustomShaderMaterial instance OWNS its RenderTypes + uniform
    // buffer and frees them when it is released (the 1.21 LDShaderHolder + Cleaner lifecycle). This
    // layer only BUILDS one and registers it into the drain's indexes; the expensive part — the
    // compiled pipeline — is still deduped globally in PhotonPipelines, so per-material ownership
    // never duplicates a GPU compile. Two identical-but-separate materials get their own RenderTypes
    // (they each own a live uniform buffer that a timeline can drive independently); share the
    // material instance if you want them to batch.

    /** Fragment shaders whose pipeline failed GPU compilation — short-circuit so a known-bad shader
     *  isn't re-precompiled every frame. Cleared per shader on recompile (see
     *  {@link #clearFailedCustomShader}). */
    private static final Set<Identifier> FAILED_CUSTOM_SHADERS = ConcurrentHashMap.newKeySet();

    /** The caller owns the result and must {@link #dropCustomShader} it; {@code liveTextures} is referenced live.
     *  Empty when the pipeline failed to compile. */
    public static Optional<RenderType> createCustomShader(PhotonPipelines.CustomShaderKey key,
                                                          PhotonCustomUniforms uniforms,
                                                          Map<String, Identifier> liveTextures,
                                                          boolean legacyDepth) {
        if (FAILED_CUSTOM_SHADERS.contains(key.fragmentShader())) {
            return Optional.empty();
        }
        var pipeline = PhotonPipelines.customShader(key);
        if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
            if (FAILED_CUSTOM_SHADERS.add(key.fragmentShader())) {
                Photon.LOGGER.warn("custom shader pipeline failed to compile: {} (must be 26.2-format GLSL: "
                        + "uniform values in blocks, no loose uniforms)", key.fragmentShader());
            }
            return Optional.empty();
        }
        var setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", MissingTextureAtlasSprite.getLocation())
                .useLightmap();
        // placeholders only; Photon binds the live textures
        key.samplerNames().forEach(name -> setup.withTexture(name, MissingTextureAtlasSprite.getLocation()));
        key.sceneSamplers().forEach(name -> setup.withTexture(name, MissingTextureAtlasSprite.getLocation()));
        if (key.pipelineKey().blend() != null && key.pipelineKey().mode() == PrimitiveTopology.QUADS) {
            setup.sortOnUpload();
        }
        var renderType = RenderType.create("photon_custom_shader", setup.createRenderSetup());
        DRAW_INFO.put(renderType, new PhotonDrawInfo(
                new PhotonDrawInfo.Programs(pipeline),
                new PhotonDrawInfo.Bindings(liveTextures, key.sceneSamplers(), uniforms, null, legacyDepth),
                new PhotonDrawInfo.InstancedRecipe(key.pipelineKey(), null, key)));
        return Optional.of(renderType);
    }

    // ---- KilaGraph shader graphs ----------------------------------------------------------------

    /** A shader graph through Photon's pipeline; owned like {@link #createCustomShader}. */
    public static Optional<RenderType> createGraphShader(
            CompiledShaderGraph compiled,
            RenderTypeGraphMaterial material,
            PhotonPipelines.ParticlePipelineKey pipelineKey,
            long usedChannelMask, boolean usesCustomData) {
        // the CPU variant reads no additional data (particle.glsl's non-instanced accessors return 0)
        var pipeline = PhotonPipelines.graphShader(compiled, null, pipelineKey);
        if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
            Photon.LOGGER.warn("shader graph pipeline failed to compile: {}", compiled.contentHash());
            return Optional.empty();
        }
        var sceneSamplers = new ArrayList<String>();
        if (compiled.usesSceneColor()) {
            sceneSamplers.add(PhotonShaderCompiler.SCENE_COLOR);
        }
        if (compiled.usesSceneDepth()) {
            sceneSamplers.add(PhotonShaderCompiler.SCENE_DEPTH);
        }
        // identity only: Photon and the graph material bind everything
        var setup = RenderSetup.builder(pipeline);
        if (compiled.usesLightmap()) {
            setup.useLightmap();
        }
        if (pipelineKey.blend() != null && pipelineKey.mode() == PrimitiveTopology.QUADS) {
            setup.sortOnUpload();
        }
        var renderType = RenderType.create("photon_shader_graph", setup.createRenderSetup());
        DRAW_INFO.put(renderType, new PhotonDrawInfo(
                new PhotonDrawInfo.Programs(pipeline),
                new PhotonDrawInfo.Bindings(Map.of(), sceneSamplers, null,
                        new GraphSource(compiled, material, usedChannelMask, usesCustomData)),
                new PhotonDrawInfo.InstancedRecipe(pipelineKey, null, null)));
        return Optional.of(renderType);
    }

    /** Release a material-owned custom RenderType from the drain index. No GPU work — the material's
     *  Cleaner closes the uniform buffer separately. Idempotent. */
    public static void dropCustomShader(RenderType renderType) {
        DRAW_INFO.remove(renderType);
    }

    /** Let a fixed shader re-attempt compilation (called on the material's recompile). */
    public static void clearFailedCustomShader(Identifier fragmentShader) {
        FAILED_CUSTOM_SHADERS.remove(fragmentShader);
    }

    // ---- resource reload ------------------------------------------------------------------------

    private static final AtomicInteger RELOAD_GENERATION =
            new AtomicInteger();

    /** Bumped on every resource reload; a {@code CustomShaderMaterial} compares against it to drop
     *  stale JSON metadata + GPU state. The GLSL itself is recompiled by the engine on reload, but
     *  the Photon-side uniform LAYOUT is parsed from the shader JSON and must be re-read. */
    public static int reloadGeneration() {
        return RELOAD_GENERATION.get();
    }

    public static void onResourceReload() {
        RELOAD_GENERATION.incrementAndGet();
        FAILED_CUSTOM_SHADERS.clear();
        PhotonPipelines.onResourceReload();
    }
}
