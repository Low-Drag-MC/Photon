package com.lowdragmc.photon.client.render;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.compiler.MaterialUniformLayout;
import com.lowdragmc.kilagraph.rendertype.compiler.ShaderGraphCompiler;
import com.lowdragmc.kilagraph.rendertype.runtime.DynamicShaderSourceRegistry;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Photon's render pipelines (26.1 model: pipeline state lives in code, shader assets are bare
 * .vsh/.fsh with std140 includes). MaterialSetting state (blend/cull/depth) is no longer imperative
 * GL state but a pipeline property, so pipelines are derived per state combination from the shared
 * snippet and cached — the device compiles unregistered pipelines lazily on first setPipeline
 * (KilaGraph's RenderTypeFactory relies on the same behavior).
 */
public final class PhotonPipelines {

    /** The GL blend equation (glBlendEquation enum). 26.1 pipelines can't express it — Photon's
     *  own drain applies non-ADD equations as a raw-GL escape around the draw (M3 decision D4-C,
     *  same accepted debt as the bloom composite). NOT a pipeline property; rides in the key so it
     *  reaches the DrawInfo/instanced derivations. */
    public static final int BLEND_EQUATION_ADD = 32774; // GL14.GL_FUNC_ADD

    /** The state that selects a pipeline variant: MaterialSetting blend/cull/depth + the emitter's
     *  primitive mode (quads for tiles/beams, TRIANGLE_STRIP for trails, TRIANGLES for ara-trails)
     *  + the editor wireframe overlay flag. Blend null = no blending; it does NOT decide when the draw
     *  happens — that is {@code RendererSetting.Layer} / {@link PhotonStage}, per emitter. */
    public record ParticlePipelineKey(@Nullable BlendFunction blend, int blendEquation,
                                      boolean cull, boolean depthTest,
                                      boolean depthMask, VertexFormat.Mode mode, boolean wireframe) {
        public static final ParticlePipelineKey DEFAULT = new ParticlePipelineKey(
                BlendFunction.TRANSLUCENT, BLEND_EQUATION_ADD, true, true, false, VertexFormat.Mode.QUADS, false);

        /** The editor wireframe overlay: unculled, undepth-tested lines over the same geometry. */
        public static ParticlePipelineKey wireframe(VertexFormat.Mode mode) {
            return new ParticlePipelineKey(BlendFunction.TRANSLUCENT, BLEND_EQUATION_ADD,
                    false, false, false, mode, true);
        }
    }

    /**
     * The CPU-baked particle vertex format: {@code DefaultVertexFormat.BLOCK} <b>plus a Normal</b>.
     * <p>
     * 1.21's stock {@code BLOCK} carried a Normal and Photon's geometry relied on it — the CPU bakers
     * write one ({@code TileParticleRenderer}) and {@code photon:particle.glsl} declares {@code in vec3
     * Normal} on the non-instanced branch. 26.1's {@code BLOCK} dropped that element, so continuing to
     * name the same constant silently discarded every normal (written into a slot the format no longer
     * has, read from an attribute nothing binds). It also made the stride disagree with KilaGraph, whose
     * "Block" preset still includes Normal: baking 28-byte vertices for a pipeline that strides 32 made
     * shader-graph materials read every vertex misaligned.
     * <p>
     * Element order/types mirror {@code VertexFormatPresets.BLOCK} on the KilaGraph side — keep in lockstep.
     */
    public static final VertexFormat PARTICLE_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV2", VertexFormatElement.UV2)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1) // MC requires the vertex size to be a multiple of 4 (31 -> 32)
            .build();

    /** The particle base: {@link #PARTICLE_FORMAT} geometry with the vanilla matrices/fog stack and the
     *  texture + lightmap samplers. */
    public static final RenderPipeline.Snippet HDR_PARTICLE_SNIPPET = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withVertexShader(Photon.id("core/particle"))
            .withFragmentShader(Photon.id("core/hdr_particle"))
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withUniform("PhotonMaterial", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(PARTICLE_FORMAT, VertexFormat.Mode.QUADS)
            .buildSnippet();

    private static final Map<HdrPipelineKey, RenderPipeline> HDR_PARTICLE_VARIANTS = new ConcurrentHashMap<>();
    private static final AtomicInteger VARIANT_ID = new AtomicInteger();

    /** Wireframe pipelines sample the scene capture (the 1.21 inverse shader) — the drain must
     *  capture before drawing them and bind {@code SamplerScene}. */
    private static final Set<RenderPipeline> WIREFRAME_PIPELINES = ConcurrentHashMap.newKeySet();

    public static boolean isWireframe(RenderPipeline pipeline) {
        return WIREFRAME_PIPELINES.contains(pipeline);
    }

    /**
     * Build, applying the editor wireframe overlay when the key asks for it: polygon mode plus the 1.21
     * {@code inverse} fragment stage over the captured scene color, tracked so the drain knows to capture
     * and bind it. The overlay is ALWAYS Photon's own program — see {@code Emitter.bakeGroup} /
     * {@code bakeInstancedGroup}, which build it from a dedicated wireframe key rather than from a
     * material's — so only these two builders ever see {@code wireframe == true}.
     */
    private static RenderPipeline build(RenderPipeline.Builder builder, boolean wireframe) {
        if (wireframe) {
            builder.withPolygonMode(PolygonMode.WIREFRAME)
                    .withFragmentShader(Photon.id("core/inverse"))
                    // PhotonEngine, not Globals: inverse.fsh divides gl_FragCoord by U_ViewPort.zw (the
                    // size of the target it is drawing into), which is what the scene capture is sized to
                    .withUniform("PhotonEngine", UniformType.UNIFORM_BUFFER)
                    .withSampler("SamplerSceneColor");
        }
        var pipeline = builder.build();
        if (wireframe) {
            WIREFRAME_PIPELINES.add(pipeline);
        }
        return pipeline;
    }

    private PhotonPipelines() {
    }

    /** Fragment-stage selection restores the 1.21 three-program structure (hdr / pixel / sprite
     *  variants are separate fsh files sharing the PhotonMaterial block). */
    public record HdrPipelineKey(Identifier fragmentShader, ParticlePipelineKey key) {
    }

    public static RenderPipeline hdrParticle(ParticlePipelineKey key) {
        return hdrParticle(Photon.id("core/hdr_particle"), key);
    }

    public static RenderPipeline hdrParticle(Identifier fragmentShader, ParticlePipelineKey key) {
        return HDR_PARTICLE_VARIANTS.computeIfAbsent(new HdrPipelineKey(fragmentShader, key), hk -> {
            var k = hk.key();
            var builder = RenderPipeline.builder(HDR_PARTICLE_SNIPPET)
                    // unique location per variant: it's the pipeline's identity in debug output/caches
                    .withLocation(Photon.id("pipeline/hdr_particle_" + VARIANT_ID.getAndIncrement()))
                    .withFragmentShader(hk.fragmentShader())
                    .withColorTargetState(k.blend() == null ? ColorTargetState.DEFAULT : new ColorTargetState(k.blend()))
                    .withDepthStencilState(new DepthStencilState(
                            k.depthTest() ? CompareOp.LESS_THAN_OR_EQUAL : CompareOp.ALWAYS_PASS,
                            k.depthMask()))
                    .withCull(k.cull())
                    .withVertexFormat(PARTICLE_FORMAT, k.mode());
            return build(builder, k.wireframe());
        });
    }

    /** The instanced geometry families: same fragment stage and PhotonMaterial UBO as
     *  {@link #hdrParticle}, but per-instance data arrives as a divisor-stepped vertex stream
     *  ({@link PhotonInstancedDrawState}) and the shader define selects the getParticleData
     *  expansion; TRAIL/ARA additionally pull per-point data from the PhotonPoints texel buffer
     *  (RGBA8-declared — 26.1 texel buffers have no float format; {@code GlConstMixin} remaps it). */
    public enum InstancedVariant {
        // DEDICATED formats (unique element names -> value-unequal -> exclusive VAOs) so the
        // divisor mixin never touches shared VAOs; real pointers are applied by the mixin
        TILE("PARTICLE_INSTANCE", instancedFormat("PhotonTileCorner"), false, true, true,
                PhotonGpuChannels.Kind.TILE, PhotonInstancedDrawState.TILE),
        MODEL("PARTICLE_MODEL_INSTANCE", instancedFormat("PhotonModelVertex"), false, true, true,
                PhotonGpuChannels.Kind.TILE_MODEL, PhotonInstancedDrawState.MODEL),
        TRAIL("TRAIL_INSTANCE", instancedFormat("PhotonTrailCorner"), true, false, false,
                PhotonGpuChannels.Kind.TRAIL, PhotonInstancedDrawState.TRAIL),
        ARA("ARA_TRAIL_INSTANCE", instancedFormat("PhotonAraCorner"), true, false, false,
                PhotonGpuChannels.Kind.ARA_TRAIL, PhotonInstancedDrawState.ARA),
        ARA_TUBE("ARA_TRAIL_TUBE_INSTANCE", instancedFormat("PhotonAraTubeCorner"), true, false, false,
                PhotonGpuChannels.Kind.ARA_TRAIL, PhotonInstancedDrawState.ARA_TUBE),
        BEAM("BEAM_INSTANCE", instancedFormat("PhotonBeamCorner"), false, false, true,
                PhotonGpuChannels.Kind.BEAM, PhotonInstancedDrawState.BEAM);

        final String define;
        final VertexFormat format;
        public final boolean usesPoints;
        /** Whether {@code particle.glsl} declares {@code PhotonCustomData} for this define — per-particle
         *  kinds only; trail/ara/beam instances aren't particles, so {@code photon_custom_data()} reads 0. */
        public final boolean usesCustomData;
        /**
         * Whether this variant's instance record STARTS with the instance's own position, which is what
         * lets {@code Emitter.bakeInstancedGroup} honour {@code SortMode.DISTANCE} by permuting whole
         * records far-to-near. False for trail/ara: their records lead with a PhotonPoints index (the
         * position lives in that buffer), and their instances are consecutive segments of one ribbon,
         * where the emit order is the meaningful one anyway. Always false when {@link #usesPoints} is
         * true — a permutation must not reorder records that index a shared point block.
         */
        public final boolean positionAtRecordHead;
        /** The additional-GPU-data kind this variant's instances are, which fixes the record packing and
         *  the base location of the attribute tail. */
        public final PhotonGpuChannels.Kind kind;
        public final PhotonInstancedDrawState.Layout layout;

        InstancedVariant(String define, VertexFormat format, boolean usesPoints,
                         boolean usesCustomData, boolean positionAtRecordHead, PhotonGpuChannels.Kind kind,
                         PhotonInstancedDrawState.Layout layout) {
            this.define = define;
            this.format = format;
            this.usesPoints = usesPoints;
            this.usesCustomData = usesCustomData;
            this.positionAtRecordHead = positionAtRecordHead;
            this.kind = kind;
            this.layout = layout;
        }
    }

    private static VertexFormat instancedFormat(String name) {
        return VertexFormat.builder().add(name, VertexFormatElement.POSITION).build();
    }

    private record InstancedKey(InstancedVariant variant, Identifier fragmentShader, ParticlePipelineKey key) {
    }

    private static final Map<InstancedKey, RenderPipeline> INSTANCED_VARIANTS = new ConcurrentHashMap<>();

    public static RenderPipeline instancedHdrParticle(InstancedVariant variant, ParticlePipelineKey key) {
        return instancedHdrParticle(variant, Photon.id("core/hdr_particle"), key);
    }

    public static RenderPipeline instancedHdrParticle(InstancedVariant variant,
                                                      @Nullable Identifier fragmentShader,
                                                      ParticlePipelineKey key) {
        var fragment = fragmentShader != null ? fragmentShader : Photon.id("core/hdr_particle");
        return INSTANCED_VARIANTS.computeIfAbsent(new InstancedKey(variant, fragment, key), ik -> {
            var k = ik.key();
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                    .withLocation(Photon.id("pipeline/hdr_particle_instanced_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(Photon.id("core/particle"))
                    .withFragmentShader(ik.fragmentShader())
                    .withSampler("Sampler0")
                    .withSampler("Sampler2")
                    .withUniform("PhotonMaterial", UniformType.UNIFORM_BUFFER)
                    .withVertexFormat(ik.variant().format, VertexFormat.Mode.QUADS)
                    .withColorTargetState(k.blend() == null ? ColorTargetState.DEFAULT : new ColorTargetState(k.blend()))
                    .withDepthStencilState(new DepthStencilState(
                            k.depthTest() ? CompareOp.LESS_THAN_OR_EQUAL : CompareOp.ALWAYS_PASS,
                            k.depthMask()))
                    .withCull(k.cull());
            builder.withShaderDefine(ik.variant().define);
            if (ik.variant().usesPoints) {
                builder.withUniform("PhotonPoints", UniformType.TEXEL_BUFFER, TextureFormat.RGBA8);
            }
            return build(builder, k.wireframe());
        });
    }

    private record MaskKey(@Nullable InstancedVariant variant, VertexFormat.Mode mode) {
    }

    private static final Map<MaskKey, RenderPipeline> MASK_VARIANTS = new ConcurrentHashMap<>();

    /**
     * The CustomMask sub-pass pipeline: the flat-id shader over the SAME geometry the material pass
     * drew, so it needs the same format/variant combination. {@code variant} null = CPU-baked
     * {@link #PARTICLE_FORMAT} geometry in the emitter's own primitive mode.
     * <p>
     * State is fixed rather than taken from the material: the mask draws opaque (an id is not a colour
     * to blend), unculled (a billboard's winding is arbitrary), and depth-tests <b>and writes</b> against
     * the mask target's own depth — that write is what makes the buffer a custom depth an effect can read.
     */
    public static RenderPipeline mask(@Nullable InstancedVariant variant, VertexFormat.Mode mode) {
        return MASK_VARIANTS.computeIfAbsent(new MaskKey(variant, mode), mk -> {
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                    .withLocation(Photon.id("pipeline/mask_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(Photon.id("core/mask"))
                    .withFragmentShader(Photon.id("core/mask"))
                    .withSampler("Sampler0")
                    .withUniform("PhotonMask", UniformType.UNIFORM_BUFFER)
                    .withVertexFormat(mk.variant() == null ? PARTICLE_FORMAT : mk.variant().format,
                            mk.variant() == null ? mk.mode() : VertexFormat.Mode.QUADS)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                    .withCull(false);
            if (mk.variant() != null) {
                builder.withShaderDefine(mk.variant().define);
                if (mk.variant().usesPoints) {
                    builder.withUniform("PhotonPoints", UniformType.TEXEL_BUFFER, TextureFormat.RGBA8);
                }
            }
            return builder.build();
        });
    }

    private record InstancedCustomKey(InstancedVariant variant, CustomShaderKey key) {
    }

    private static final Map<InstancedCustomKey, RenderPipeline> INSTANCED_CUSTOM_VARIANTS = new ConcurrentHashMap<>();

    /** The user's own vertex+fragment stages compiled with the engine instance define + dedicated
     *  vertex format (the 1.21 combination — a custom vsh calls {@code getParticleData()} which
     *  expands to the instanced read under the define; {@code tornado_body.vsh} even branches on
     *  {@code PARTICLE_MODEL_INSTANCE}). Falls back to the shared vsh when the JSON declared none. */
    public static RenderPipeline instancedCustomShader(InstancedVariant variant, CustomShaderKey key) {
        return INSTANCED_CUSTOM_VARIANTS.computeIfAbsent(new InstancedCustomKey(variant, key), ik -> {
            var k = ik.key();
            var pk = k.pipelineKey();
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                    .withLocation(Photon.id("pipeline/custom_instanced_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(k.vertexShader())
                    .withFragmentShader(k.fragmentShader())
                    .withSampler("Sampler0")
                    .withSampler("Sampler2")
                    .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    .withUniform("PhotonEngine", UniformType.UNIFORM_BUFFER)
                    .withUniform("PhotonCustomMaterial", UniformType.UNIFORM_BUFFER)
                    // the instanced vertex stage still reads the PhotonMaterial block
                    .withUniform("PhotonMaterial", UniformType.UNIFORM_BUFFER)
                    .withVertexFormat(ik.variant().format, VertexFormat.Mode.QUADS)
                    .withColorTargetState(pk.blend() == null ? ColorTargetState.DEFAULT : new ColorTargetState(pk.blend()))
                    .withDepthStencilState(new DepthStencilState(
                            pk.depthTest() ? CompareOp.LESS_THAN_OR_EQUAL : CompareOp.ALWAYS_PASS,
                            pk.depthMask()))
                    .withCull(pk.cull());
            builder.withShaderDefine(ik.variant().define);
            if (ik.variant().usesPoints) {
                builder.withUniform("PhotonPoints", UniformType.TEXEL_BUFFER, TextureFormat.RGBA8);
            }
            k.defines().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> builder.withShaderDefine(e.getKey(), e.getValue()));
            k.samplerNames().forEach(builder::withSampler);
            k.sceneSamplers().forEach(builder::withSampler);
            return builder.build();
        });
    }

    // ---- KilaGraph shader graphs ----------------------------------------------------------------
    //
    // 1.21 kept the same split we restore here: KilaGraph is the GLSL GENERATOR, Photon owns the draw.
    // There, KG produced a CompiledShaderGraph and Photon built an LDShaderInstance per #define variant
    // (KGShaderResourceProvider), then drew it through its own pass — so graph materials automatically got
    // MaterialSetting's blend/depth/cull, bloom and GPU instancing, exactly like every other material.
    // Using KG's own RenderType instead (the shortcut this replaces) moved the draw into KilaGraph and lost
    // all three. So: KG's generated source is registered under an Identifier
    // (DynamicShaderSourceRegistry.shaderId) and we compile OUR pipeline from it, declaring the blocks and
    // samplers the generated GLSL references (the material binds their values at draw).

    private record GraphKey(String contentHash, @Nullable InstancedVariant variant, ParticlePipelineKey key) {
    }

    private static final Map<GraphKey, RenderPipeline> GRAPH_VARIANTS = new ConcurrentHashMap<>();

    /**
     * A Photon pipeline over a compiled shader graph. {@code variant} null = the CPU-baked
     * {@link #PARTICLE_FORMAT} geometry; otherwise the instanced format + its {@code #define}, which is what
     * lets {@code getParticleData()} in {@code photon:particle.glsl} expand to the instanced attribute read.
     * <p>
     * {@code usedChannelMask}/{@code usesCustomData} come from the same compile as {@code compiled} (they are
     * a function of its {@code contentHash}, so they need no place in the cache key) and decide whether the
     * additional-data texel buffers are declared.
     */
    public static RenderPipeline graphShader(CompiledShaderGraph compiled,
                                             @Nullable InstancedVariant variant, ParticlePipelineKey key,
                                             long usedChannelMask, boolean usesCustomData) {
        return GRAPH_VARIANTS.computeIfAbsent(new GraphKey(compiled.contentHash(), variant, key), gk -> {
            var k = gk.key();
            var builder = RenderPipeline.builder()
                    .withLocation(Photon.id("pipeline/graph_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(DynamicShaderSourceRegistry
                            .shaderId(compiled.contentHash()))
                    .withFragmentShader(DynamicShaderSourceRegistry
                            .shaderId(compiled.contentHash()))
                    // CPU geometry keeps the emitter's primitive mode (trails are strips, ara-trails
                    // triangles); the instanced variants always expand a quad-indexed base mesh
                    .withVertexFormat(variant == null ? PARTICLE_FORMAT : variant.format,
                            variant == null ? key.mode() : VertexFormat.Mode.QUADS)
                    .withColorTargetState(k.blend() == null ? ColorTargetState.DEFAULT : new ColorTargetState(k.blend()))
                    .withDepthStencilState(new DepthStencilState(
                            k.depthTest() ? CompareOp.LESS_THAN_OR_EQUAL : CompareOp.ALWAYS_PASS,
                            k.depthMask()))
                    .withCull(k.cull());
            // exactly what the generated GLSL references — mirrors KilaGraph's own RenderTypeFactory
            for (var ubo : compiled.builtinUniforms()) {
                builder.withUniform(ubo, UniformType.UNIFORM_BUFFER);
            }
            if (!compiled.layout().isEmpty()) {
                builder.withUniform(MaterialUniformLayout.UBO_NAME,
                        UniformType.UNIFORM_BUFFER);
            }
            for (var block : compiled.uniformBlocks()) {
                builder.withUniform(block.uboName(), UniformType.UNIFORM_BUFFER);
            }
            for (var sampler : compiled.layout().samplers()) {
                builder.withSampler(sampler);
            }
            // Photon-named scene captures (PhotonShaderCompiler renames them off KilaGraph's own for
            // non-preview compiles) — bound by the drain from its pre-fx capture
            if (compiled.usesSceneColor()) {
                builder.withSampler(PhotonShaderCompiler.SCENE_COLOR);
            }
            if (compiled.usesSceneDepth()) {
                builder.withSampler(PhotonShaderCompiler.SCENE_DEPTH);
            }
            if (variant != null) {
                builder.withShaderDefine(variant.define);
                if (variant.usesPoints) {
                    builder.withUniform("PhotonPoints", UniformType.TEXEL_BUFFER, TextureFormat.RGBA8);
                }
                // Additional GPU data — declared ONLY when the generated GLSL reads it, because a declared
                // uniform must be bound at draw. The emitter's want-decision uses the same graph flags
                // (unioned across the pass's materials), so "declared" always implies "bound".
                if (usedChannelMask != 0L) {
                    builder.withUniform("PhotonData", UniformType.TEXEL_BUFFER, TextureFormat.RGBA8);
                }
                if (usesCustomData && variant.usesCustomData) {
                    builder.withUniform("PhotonCustomData", UniformType.TEXEL_BUFFER, TextureFormat.RGBA8);
                }
            }
            return builder.build();
        });
    }

    private static final Map<String, RenderPipeline> FULLSCREEN_GRAPHS = new ConcurrentHashMap<>();

    /**
     * A Photon pipeline over a compiled FULLSCREEN shader graph — one post-effect pass. Same GLSL source
     * KilaGraph registered for the graph, but our own state, taken from {@link PhotonFullscreenPass#builder()}
     * so there is one definition of what a fullscreen pipeline is. KilaGraph's own pipeline for the same
     * graph cannot be reused: its {@code depthState} is never null, and a post-effect target has no depth
     * attachment, so every draw would warn.
     */
    public static RenderPipeline fullscreenGraph(
            CompiledShaderGraph compiled) {
        return FULLSCREEN_GRAPHS.computeIfAbsent(compiled.contentHash(), hash -> {
            var shaderId = DynamicShaderSourceRegistry.shaderId(hash);
            var builder = PhotonFullscreenPass.builder()
                    .withLocation(Photon.id("pipeline/postfx_graph_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(shaderId)
                    .withFragmentShader(shaderId)
                    .withColorTargetState(ColorTargetState.DEFAULT);
            for (var ubo : compiled.builtinUniforms()) {
                builder.withUniform(ubo, UniformType.UNIFORM_BUFFER);
            }
            if (!compiled.layout().isEmpty()) {
                builder.withUniform(MaterialUniformLayout.UBO_NAME,
                        UniformType.UNIFORM_BUFFER);
            }
            for (var block : compiled.uniformBlocks()) {
                builder.withUniform(block.uboName(), UniformType.UNIFORM_BUFFER);
            }
            for (var sampler : compiled.layout().samplers()) {
                builder.withSampler(sampler);
            }
            // a fullscreen graph keeps KilaGraph's own scene-sampler names (PhotonShaderCompiler's
            // rename is particle-side only), so KilaGraph's bindCustomUniforms binds them for us
            if (compiled.usesSceneColor()) {
                builder.withSampler(ShaderGraphCompiler.SCENE_COLOR_SAMPLER);
            }
            if (compiled.usesSceneDepth()) {
                builder.withSampler(ShaderGraphCompiler.SCENE_DEPTH_SAMPLER);
            }
            return builder.build();
        });
    }

    public static void register(RegisterRenderPipelinesEvent event) {
        // pre-register the default-material variant so startup precompiles the common case;
        // every other variant lazy-compiles on first draw
        event.registerPipeline(hdrParticle(ParticlePipelineKey.DEFAULT));
    }

    // ---- custom user shaders (26.1 contract: user vsh + fsh, 1.21 JSON is the metadata) ----

    /** The PURELY STRUCTURAL pipeline identity for a custom-shader material — everything that
     *  affects COMPILATION, nothing that is a runtime value:
     *  <ul>
     *    <li>{@code vertexShader}/{@code fragmentShader}: the 1.21 JSON's own {@code vertex}/{@code
     *        fragment} programs (custom vsh restored — was hardcoded to {@code core/particle});</li>
     *    <li>{@code defines}: compile-time VARIANTS only (the engine instance/mode selection — the
     *        1.21 {@code getShader(defines)} derivation);</li>
     *    <li>{@code samplerNames}: sampler DECLARATIONS (sorted names) — never the bound textures;
     *        texture bindings are dynamic per-material state applied at draw;</li>
     *    <li>{@code sceneSamplers}: {@code SamplerScene*} declaration names (drain binds the capture);</li>
     *    <li>{@code pipelineKey}: MaterialSetting blend/cull/depth/mode state.</li>
     *  </ul>
     *  So ONE pipeline is shared across every material instance and every uniform/texture value using
     *  the same shader+mode+state — editing a uniform or swapping a sampler texture never recompiles. */
    public record CustomShaderKey(Identifier vertexShader,
                                  Identifier fragmentShader,
                                  Map<String, Float> defines,
                                  List<String> samplerNames,
                                  List<String> sceneSamplers,
                                  ParticlePipelineKey pipelineKey) {
    }

    private static final Map<CustomShaderKey, RenderPipeline> CUSTOM_SHADER_VARIANTS = new ConcurrentHashMap<>();

    /**
     * A pipeline over the user's own vertex stage ({@code key.vertexShader()}; the shared
     * {@code photon:core/particle} when the JSON declares no custom vsh) with the user's fragment
     * stage. Custom uniform VALUES live in the per-material {@code PhotonCustomMaterial} UBO (bound at
     * draw), textures bind per-material at draw — neither is part of this key, so the pipeline is
     * shared and value edits never recompile.
     */
    public static RenderPipeline customShader(CustomShaderKey key) {
        return CUSTOM_SHADER_VARIANTS.computeIfAbsent(key, k -> {
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                    .withLocation(Photon.id("pipeline/custom_shader_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(k.vertexShader())
                    .withFragmentShader(k.fragmentShader())
                    .withSampler("Sampler0")
                    .withSampler("Sampler2")
                    // GameTime/ScreenSize for user shaders (bound by bindDefaultUniforms when declared)
                    .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    // the 1.21 U_* dynamic uniforms (photon:engine.glsl; bound by RenderTypeMixin)
                    .withUniform("PhotonEngine", UniformType.UNIFORM_BUFFER)
                    .withUniform("PhotonCustomMaterial", UniformType.UNIFORM_BUFFER)
                    .withVertexFormat(PARTICLE_FORMAT, k.pipelineKey().mode())
                    .withColorTargetState(k.pipelineKey().blend() == null
                            ? ColorTargetState.DEFAULT : new ColorTargetState(k.pipelineKey().blend()))
                    .withDepthStencilState(new DepthStencilState(
                            k.pipelineKey().depthTest() ? CompareOp.LESS_THAN_OR_EQUAL : CompareOp.ALWAYS_PASS,
                            k.pipelineKey().depthMask()))
                    .withCull(k.pipelineKey().cull());
            // sorted for a deterministic define order (the map's own equality drives the cache)
            k.defines().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> builder.withShaderDefine(e.getKey(), e.getValue()));
            k.samplerNames().forEach(builder::withSampler);
            k.sceneSamplers().forEach(builder::withSampler);
            return builder.build();
        });
    }
}
