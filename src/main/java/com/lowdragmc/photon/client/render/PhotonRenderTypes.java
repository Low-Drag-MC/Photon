package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The render-layer index the drain reads to draw Photon geometry manually: RenderType →
 * {@link PhotonDrawInfo}. It also holds the build/drop primitives for the PER-MATERIAL-OWNED custom
 * shaders (those RenderTypes are NOT cached here — {@code CustomShaderMaterial} owns them). The
 * shared, value-based factory for the standard HDR materials lives in the material layer, in
 * {@code MaterialRenderTypes}.
 */
public final class PhotonRenderTypes {

    /** What Photon's OWN drain needs to draw a render type manually (ring-buffer fast path):
     *  the pipeline + explicit texture bindings + the depth-write-off variant used when drawing
     *  the same geometry into the bloom source. Absent (e.g. KilaGraph graph types) → the drain
     *  falls back to {@code RenderType.draw}, keeping foreign draw hooks (KG's mixin) intact. */
    public record PhotonDrawInfo(Programs programs, Bindings bindings,
                                 @Nullable InstancedRecipe instanced) {

        /** The two compiled programs: the main draw, and the bloom re-draw (same state, depth
         *  writes off) that replays the geometry into the bloom source. */
        public record Programs(RenderPipeline main, RenderPipeline bloom) {
        }

        /**
         * Everything a render pass must bind for this material. Photon opens its own passes, so none
         * of this comes from the RenderSetup: textures by {@link Identifier} (resolved before the pass
         * opens — first use uploads), the {@code SamplerScene*} names the drain fills from its capture,
         * the material's own custom UBO, and the KilaGraph material that owns a shader graph's values.
         */
        public record Bindings(Map<String, Identifier> textures,
                               java.util.List<String> sceneSamplers,
                               @Nullable PhotonCustomUniforms customUniforms,
                               @Nullable GraphSource graph) {
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
    public record GraphSource(com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph compiled,
                              com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeGraphMaterial material,
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

    /** Build (do NOT cache) a RenderType for one custom-shader draw variant and register it into the
     *  drain indexes ({@link #DRAW_INFO} + the vanilla-phase uniform registries). The caller — the
     *  material instance — owns it and MUST {@link #dropCustomShader} it on release. {@code liveTextures}
     *  is the material's own mutable sampler map (referenced live, so a texture swap needs no rebuild).
     *  Empty when the pipeline failed to compile (logged once). */
    public static Optional<RenderType> createCustomShader(PhotonPipelines.CustomShaderKey key,
                                                          PhotonCustomUniforms uniforms,
                                                          Map<String, Identifier> liveTextures) {
        if (FAILED_CUSTOM_SHADERS.contains(key.fragmentShader())) {
            return Optional.empty();
        }
        var pipeline = PhotonPipelines.customShader(key);
        if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
            if (FAILED_CUSTOM_SHADERS.add(key.fragmentShader())) {
                Photon.LOGGER.warn("custom shader pipeline failed to compile: {} (must be 26.1-format GLSL)",
                        key.fragmentShader());
            }
            return Optional.empty();
        }
        var setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", MissingTextureAtlasSprite.getLocation())
                .useLightmap();
        // every pipeline-declared sampler must be bound at draw; the vanilla-phase RenderSetup gets a
        // placeholder per name (Photon's own drain re-binds the live textures/captures)
        key.samplerNames().forEach(name -> setup.withTexture(name, MissingTextureAtlasSprite.getLocation()));
        key.sceneSamplers().forEach(name -> setup.withTexture(name, MissingTextureAtlasSprite.getLocation()));
        if (key.pipelineKey().blend() != null && key.pipelineKey().mode() == VertexFormat.Mode.QUADS) {
            setup.sortOnUpload();
        }
        var renderType = RenderType.create("photon_custom_shader", setup.createRenderSetup());
        PhotonEngineUniforms.register(renderType);
        PhotonCustomUniforms.register(renderType, uniforms); // vanilla-phase mixin bind
        var bloomPipelineKey = new PhotonPipelines.ParticlePipelineKey(
                key.pipelineKey().blend(), key.pipelineKey().blendEquation(),
                key.pipelineKey().cull(), key.pipelineKey().depthTest(),
                false, key.pipelineKey().mode(), key.pipelineKey().wireframe());
        var bloomPipeline = PhotonPipelines.customShader(new PhotonPipelines.CustomShaderKey(
                key.vertexShader(), key.fragmentShader(), key.defines(), key.samplerNames(),
                key.sceneSamplers(), bloomPipelineKey));
        DRAW_INFO.put(renderType, new PhotonDrawInfo(
                new PhotonDrawInfo.Programs(pipeline, bloomPipeline),
                new PhotonDrawInfo.Bindings(liveTextures, key.sceneSamplers(), uniforms, null),
                new PhotonDrawInfo.InstancedRecipe(key.pipelineKey(), null, key)));
        return Optional.of(renderType);
    }

    // ---- KilaGraph shader graphs ----------------------------------------------------------------

    /**
     * Build a RenderType that draws a compiled shader graph through PHOTON's pipeline, with the emitter's
     * own {@code MaterialSetting} state. That is what restores bloom participation, blend/depth/cull and the
     * instanced variants for graph materials — using KilaGraph's own RenderType instead hands the whole draw
     * to KilaGraph, which knows nothing about any of them. Owned by the calling material (see
     * {@link #dropCustomShader}). Empty when the pipeline fails to compile.
     */
    public static Optional<RenderType> createGraphShader(
            com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph compiled,
            com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeGraphMaterial material,
            PhotonPipelines.ParticlePipelineKey pipelineKey,
            long usedChannelMask, boolean usesCustomData) {
        // the CPU variant reads no additional data (particle.glsl's non-instanced accessors return 0)
        var pipeline = PhotonPipelines.graphShader(compiled, null, pipelineKey, usedChannelMask, usesCustomData);
        if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
            Photon.LOGGER.warn("shader graph pipeline failed to compile: {}", compiled.contentHash());
            return Optional.empty();
        }
        var sceneSamplers = new java.util.ArrayList<String>();
        if (compiled.usesSceneColor()) {
            sceneSamplers.add(com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler.SCENE_COLOR);
        }
        if (compiled.usesSceneDepth()) {
            sceneSamplers.add(com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler.SCENE_DEPTH);
        }
        // The vanilla-phase RenderSetup only needs placeholders: Photon's drain rebinds everything, and the
        // graph's own samplers/UBOs are bound by the material.
        var setup = RenderSetup.builder(pipeline);
        if (compiled.usesLightmap()) {
            setup.useLightmap();
        }
        if (pipelineKey.blend() != null && pipelineKey.mode() == VertexFormat.Mode.QUADS) {
            setup.sortOnUpload();
        }
        var renderType = RenderType.create("photon_shader_graph", setup.createRenderSetup());
        var bloomKey = new PhotonPipelines.ParticlePipelineKey(
                pipelineKey.blend(), pipelineKey.blendEquation(), pipelineKey.cull(),
                pipelineKey.depthTest(), false, pipelineKey.mode(), pipelineKey.wireframe());
        DRAW_INFO.put(renderType, new PhotonDrawInfo(
                new PhotonDrawInfo.Programs(pipeline,
                        PhotonPipelines.graphShader(compiled, null, bloomKey, usedChannelMask, usesCustomData)),
                new PhotonDrawInfo.Bindings(Map.of(), sceneSamplers, null,
                        new GraphSource(compiled, material, usedChannelMask, usesCustomData)),
                new PhotonDrawInfo.InstancedRecipe(pipelineKey, null, null)));
        return Optional.of(renderType);
    }

    /** Release a material-owned custom RenderType from the drain indexes. No GL work — the material's
     *  Cleaner closes the uniform buffer separately. Idempotent. */
    public static void dropCustomShader(RenderType renderType) {
        PhotonCustomUniforms.unregister(renderType);
        PhotonEngineUniforms.unregister(renderType);
        DRAW_INFO.remove(renderType);
    }

    /** Let a fixed shader re-attempt compilation (called on the material's recompile). */
    public static void clearFailedCustomShader(Identifier fragmentShader) {
        FAILED_CUSTOM_SHADERS.remove(fragmentShader);
    }

    // ---- resource reload ------------------------------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicInteger RELOAD_GENERATION =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Bumped on every resource reload; a {@code CustomShaderMaterial} compares against it to drop
     *  stale JSON metadata + GPU state. The GLSL itself is recompiled by the engine on reload, but
     *  the Photon-side uniform LAYOUT is parsed from the shader JSON and must be re-read. */
    public static int reloadGeneration() {
        return RELOAD_GENERATION.get();
    }

    public static void onResourceReload() {
        RELOAD_GENERATION.incrementAndGet();
        FAILED_CUSTOM_SHADERS.clear();
    }
}
