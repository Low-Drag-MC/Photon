package com.lowdragmc.photon.client.render;

import com.lowdragmc.kilagraph.rendertype.compiler.CompiledShaderGraph;
import com.lowdragmc.kilagraph.rendertype.compiler.MaterialUniformLayout;
import com.lowdragmc.kilagraph.rendertype.runtime.DynamicShaderSourceRegistry;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.PhotonGpuChannels;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Photon's render pipelines, derived per state combination and cached.
 * <p>
 * Every layout names exactly what its shaders declare: Vulkan keeps unused declarations, so optional resources
 * sit behind a define set together with the layout entry. Colour targets use {@link #HDR_FORMAT}; depth is
 * reverse-Z.
 */
public final class PhotonPipelines {

    /** Colour format of Photon's draw targets; float so HDR colours survive to bloom. */
    public static final GpuFormat HDR_FORMAT = GpuFormat.RGBA16_FLOAT;

    /** Photon's texel buffers carry raw floats read with {@code texelFetch}. */
    public static final GpuFormat TEXEL_FORMAT = GpuFormat.RGBA32_FLOAT;

    /** Enables the mesh tangent attribute. Mirrored in {@code photon:particle.glsl}. */
    public static final String TANGENT_DEFINE = "PHOTON_TANGENT";

    /**
     * Compiles the depth fade in. A define rather than a uniform branch, since declaring {@code SamplerSceneDepth}
     * costs a scene depth copy that frame. Mirrored in {@code photon:soft_particle.glsl}.
     */
    public static final String SOFT_PARTICLE_DEFINE = "PHOTON_SOFT";

    /** Poses models from the baked pose table. Mirrored in {@code photon:particle.glsl}. */
    public static final String VAT_DEFINE = "PHOTON_VAT";

    /** Declares the {@code PhotonData} records; set exactly when the layout carries the buffer. */
    public static final String DATA_DEFINE = "PHOTON_DATA";

    /** Declares {@code PhotonCustomData}; same contract as {@link #DATA_DEFINE}. */
    public static final String CUSTOM_DATA_DEFINE = "PHOTON_CUSTOM_DATA";

    private static final String SCENE_COLOR = "SamplerSceneColor";

    /** The wireframe overlay replaces the fragment stage, so it never fades. */
    private static boolean usesSoftParticles(ParticlePipelineKey key) {
        return key.softParticles() && !key.wireframe();
    }

    /**
     * Pipeline state of a draw: MaterialSetting blend/cull/depth, topology, wireframe overlay and soft-particle
     * fade. {@code blend} null = no blending.
     */
    public record ParticlePipelineKey(@Nullable BlendFunction blend,
                                      boolean cull, boolean depthTest, boolean depthMask,
                                      PrimitiveTopology mode, boolean wireframe, boolean softParticles) {
        public static final ParticlePipelineKey DEFAULT = new ParticlePipelineKey(
                BlendFunction.TRANSLUCENT, true, true, false, PrimitiveTopology.QUADS, false, false);

        public ParticlePipelineKey(@Nullable BlendFunction blend, boolean cull, boolean depthTest,
                                   boolean depthMask, PrimitiveTopology mode, boolean wireframe) {
            this(blend, cull, depthTest, depthMask, mode, wireframe, false);
        }

        /** The editor wireframe overlay: unculled, undepth-tested lines over the same geometry. */
        public static ParticlePipelineKey wireframe(PrimitiveTopology mode) {
            return new ParticlePipelineKey(BlendFunction.TRANSLUCENT, false, false, false, mode, true, false);
        }

        /** The fade is a material property, unlike the rest of the key. */
        public ParticlePipelineKey withSoftParticles(boolean soft) {
            return soft == softParticles ? this : new ParticlePipelineKey(
                    blend, cull, depthTest, depthMask, mode, wireframe, soft);
        }

        public ParticlePipelineKey withBlend(@Nullable BlendFunction blend) {
            return new ParticlePipelineKey(blend, cull, depthTest, depthMask, mode, wireframe, softParticles);
        }
    }

    /** {@code BLOCK} plus a Normal; must match KilaGraph's {@code VertexFormatPresets.BLOCK}. */
    public static final VertexFormat PARTICLE_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("UV2", GpuFormat.RG16_SINT)
            .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
            .build();

    private static final AtomicInteger VARIANT_ID = new AtomicInteger();

    /** Wireframe pipelines sample the scene colour capture. */
    private static final Set<RenderPipeline> WIREFRAME_PIPELINES = ConcurrentHashMap.newKeySet();

    public static boolean isWireframe(RenderPipeline pipeline) {
        return WIREFRAME_PIPELINES.contains(pipeline);
    }

    private PhotonPipelines() {
    }

    // ---- layout plumbing -------------------------------------------------------------------------

    /** Names {@code MATRICES_FOG_SNIPPET} already declares. */
    private static final Set<String> SNIPPET_NAMES = Set.of("Globals", "DynamicTransforms", "Projection", "Fog");

    /** One pipeline's own bind group, deduplicated by name (GL rejects a name declared twice). */
    private static final class LayoutBuilder {
        private enum Kind {UNIFORM, TEXEL, SAMPLER}

        private record Entry(Kind kind, @Nullable GpuFormat format) {
        }

        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final Set<String> reserved;

        LayoutBuilder(Set<String> reserved) {
            this.reserved = reserved;
        }

        LayoutBuilder uniform(String name) {
            return add(name, new Entry(Kind.UNIFORM, null));
        }

        LayoutBuilder texel(String name) {
            return add(name, new Entry(Kind.TEXEL, TEXEL_FORMAT));
        }

        LayoutBuilder sampler(String name) {
            return add(name, new Entry(Kind.SAMPLER, null));
        }

        private LayoutBuilder add(String name, Entry entry) {
            if (reserved.contains(name)) {
                return this;
            }
            var previous = entries.putIfAbsent(name, entry);
            if (previous != null && !previous.equals(entry)) {
                throw new IllegalStateException("Photon pipeline declares " + name + " as both "
                        + previous.kind() + " and " + entry.kind());
            }
            return this;
        }

        void applyTo(RenderPipeline.Builder builder) {
            if (entries.isEmpty()) {
                return;
            }
            var layout = BindGroupLayout.builder();
            entries.forEach((name, entry) -> {
                switch (entry.kind()) {
                    case UNIFORM -> layout.withUniform(name, UniformType.UNIFORM_BUFFER);
                    case TEXEL -> layout.withUniform(name, UniformType.TEXEL_BUFFER, entry.format());
                    case SAMPLER -> layout.withSampler(name);
                }
            });
            builder.withBindGroupLayout(layout.build());
        }
    }

    private static ColorTargetState hdrTarget(@Nullable BlendFunction blend) {
        return new ColorTargetState(Optional.ofNullable(blend), HDR_FORMAT, ColorTargetState.WRITE_ALL);
    }

    /** Reverse-Z: nearer is greater. */
    private static DepthStencilState depthState(ParticlePipelineKey key) {
        return new DepthStencilState(key.depthTest() ? CompareOp.GREATER_THAN_OR_EQUAL : CompareOp.ALWAYS_PASS,
                key.depthMask());
    }

    private static void applySoftParticles(RenderPipeline.Builder builder, LayoutBuilder layout,
                                           ParticlePipelineKey key) {
        if (usesSoftParticles(key)) {
            builder.withShaderDefine(SOFT_PARTICLE_DEFINE);
            layout.sampler(PhotonShaderCompiler.SCENE_DEPTH).uniform(PhotonEngineUniforms.UBO_NAME);
        }
    }

    /** Editor wireframe overlay: the 1.21 inverse shader over the captured scene colour. */
    private static void applyWireframe(RenderPipeline.Builder builder, LayoutBuilder layout,
                                       ParticlePipelineKey key) {
        if (key.wireframe()) {
            builder.withPolygonMode(PolygonMode.WIREFRAME)
                    .withFragmentShader(Photon.id("core/inverse"));
            // inverse.fsh reads the target size from PhotonEngine's U_ViewPort
            layout.uniform(PhotonEngineUniforms.UBO_NAME).sampler(SCENE_COLOR);
        }
    }

    private static RenderPipeline track(RenderPipeline pipeline, boolean wireframe) {
        if (wireframe) {
            WIREFRAME_PIPELINES.add(pipeline);
        }
        return pipeline;
    }

    /** Side buffers the variant's vertex stage reads; a VAT pose always reads the records. */
    private static void applyInstanced(RenderPipeline.Builder builder, LayoutBuilder layout,
                                       InstancedVariant variant, boolean data, boolean customData) {
        variant.defines.forEach(builder::withShaderDefine);
        if (variant.usesPoints) {
            layout.texel("PhotonPoints");
        }
        if (variant.usesVat()) {
            layout.texel("PhotonVat").uniform(PhotonVatUniforms.UBO_NAME);
        }
        if (data || variant.usesVat()) {
            builder.withShaderDefine(DATA_DEFINE);
            layout.texel("PhotonData");
        }
        if (customData && variant.usesCustomData) {
            builder.withShaderDefine(CUSTOM_DATA_DEFINE);
            layout.texel("PhotonCustomData");
        }
    }

    private static void applyInstancedGeometry(RenderPipeline.Builder builder, PhotonInstanceLayouts.Layout layout) {
        applyInstancedGeometry(builder, layout, Map.of());
    }

    /** {@code tailInputs}: attribute-tail location → the custom shader's input name. */
    private static void applyInstancedGeometry(RenderPipeline.Builder builder, PhotonInstanceLayouts.Layout layout,
                                               Map<Integer, String> tailInputs) {
        builder.withVertexBinding(0, layout.baseFormat())
                .withVertexBinding(1, layout.instanceFormat(tailInputs))
                // base meshes use the shared quad indices or the ara tube's own
                .withPrimitiveTopology(PrimitiveTopology.QUADS);
    }

    // ---- Photon's own particle programs -----------------------------------------------------------

    /** Fragment-stage selection restores the 1.21 three-program structure (hdr / pixel / sprite variants are
     *  separate fsh files sharing the PhotonMaterial block). */
    private record HdrPipelineKey(Identifier fragmentShader, ParticlePipelineKey key) {
    }

    private static final Map<HdrPipelineKey, RenderPipeline> HDR_PARTICLE_VARIANTS = new ConcurrentHashMap<>();

    public static RenderPipeline hdrParticle(ParticlePipelineKey key) {
        return hdrParticle(Photon.id("core/hdr_particle"), key);
    }

    /** The CPU-baked {@link #PARTICLE_FORMAT} geometry through one of Photon's own fragment stages. */
    public static RenderPipeline hdrParticle(Identifier fragmentShader, ParticlePipelineKey key) {
        return HDR_PARTICLE_VARIANTS.computeIfAbsent(new HdrPipelineKey(fragmentShader, key), hk -> {
            var k = hk.key();
            var layout = new LayoutBuilder(SNIPPET_NAMES)
                    .sampler("Sampler0").sampler("Sampler2")
                    .uniform(PhotonMaterialUniforms.UBO_NAME);
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                    // unique location per variant: it's the pipeline's identity in debug output/caches
                    .withLocation(Photon.id("pipeline/hdr_particle_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(Photon.id("core/particle"))
                    .withFragmentShader(hk.fragmentShader())
                    .withVertexBinding(0, PARTICLE_FORMAT)
                    .withPrimitiveTopology(k.mode())
                    .withColorTargetState(hdrTarget(k.blend()))
                    .withDepthStencilState(depthState(k))
                    .withCull(k.cull());
            applySoftParticles(builder, layout, k);
            applyWireframe(builder, layout, k);
            layout.applyTo(builder);
            return track(builder.build(), k.wireframe());
        });
    }

    /** Instanced geometry families; the define selects the {@code getParticleData()} expansion. */
    public enum InstancedVariant {
        TILE(List.of("PARTICLE_INSTANCE"), false, true, true,
                PhotonGpuChannels.Kind.TILE, PhotonInstanceLayouts.TILE),
        MODEL(List.of("PARTICLE_MODEL_INSTANCE"), false, true, true,
                PhotonGpuChannels.Kind.TILE_MODEL, PhotonInstanceLayouts.MODEL),
        /** {@link #MODEL} with the mesh tangent; a variant because it changes the base vertex format. */
        MODEL_TANGENT(List.of("PARTICLE_MODEL_INSTANCE", TANGENT_DEFINE), false, true, true,
                PhotonGpuChannels.Kind.TILE_MODEL, PhotonInstanceLayouts.MODEL_TANGENT),
        /** {@link #MODEL} posed per particle from the baked pose table. */
        MODEL_VAT(List.of("PARTICLE_MODEL_INSTANCE", VAT_DEFINE), false, true, true,
                PhotonGpuChannels.Kind.TILE_MODEL, PhotonInstanceLayouts.MODEL),
        MODEL_VAT_TANGENT(List.of("PARTICLE_MODEL_INSTANCE", VAT_DEFINE, TANGENT_DEFINE), false, true, true,
                PhotonGpuChannels.Kind.TILE_MODEL, PhotonInstanceLayouts.MODEL_TANGENT),
        TRAIL(List.of("TRAIL_INSTANCE"), true, false, false,
                PhotonGpuChannels.Kind.TRAIL, PhotonInstanceLayouts.TRAIL),
        ARA(List.of("ARA_TRAIL_INSTANCE"), true, false, false,
                PhotonGpuChannels.Kind.ARA_TRAIL, PhotonInstanceLayouts.ARA),
        ARA_TUBE(List.of("ARA_TRAIL_TUBE_INSTANCE"), true, false, false,
                PhotonGpuChannels.Kind.ARA_TRAIL, PhotonInstanceLayouts.ARA_TUBE),
        BEAM(List.of("BEAM_INSTANCE"), false, false, true,
                PhotonGpuChannels.Kind.BEAM, PhotonInstanceLayouts.BEAM);

        public final List<String> defines;
        public final boolean usesPoints;
        /** Trail/ara/beam instances are not particles and carry no custom data. */
        public final boolean usesCustomData;
        /** Records start with the position, so distance sorting can permute whole records. */
        public final boolean positionAtRecordHead;
        public final PhotonGpuChannels.Kind kind;
        /** At the variant's own stride, without an attribute tail. */
        public final PhotonInstanceLayouts.Layout layout;

        public boolean usesVat() {
            return defines.contains(VAT_DEFINE);
        }

        InstancedVariant(List<String> defines, boolean usesPoints, boolean usesCustomData,
                         boolean positionAtRecordHead, PhotonGpuChannels.Kind kind,
                         PhotonInstanceLayouts.Layout layout) {
            this.defines = defines;
            this.usesPoints = usesPoints;
            this.usesCustomData = usesCustomData;
            this.positionAtRecordHead = positionAtRecordHead;
            this.kind = kind;
            this.layout = layout;
        }
    }

    /** A variant at the stride the emitter wrote, plus whether the pass uploaded the data records. */
    public record InstancedGeometryKey(InstancedVariant variant, PhotonInstanceLayouts.Layout layout,
                                       boolean data, boolean customData) {
        public static InstancedGeometryKey of(InstancedVariant variant) {
            return new InstancedGeometryKey(variant, variant.layout, false, false);
        }
    }

    private record InstancedKey(InstancedGeometryKey geometry, Identifier fragmentShader, ParticlePipelineKey key) {
    }

    private static final Map<InstancedKey, RenderPipeline> INSTANCED_VARIANTS = new ConcurrentHashMap<>();

    public static RenderPipeline instancedHdrParticle(InstancedGeometryKey geometry, ParticlePipelineKey key) {
        return instancedHdrParticle(geometry, Photon.id("core/hdr_particle"), key);
    }

    public static RenderPipeline instancedHdrParticle(InstancedGeometryKey geometry,
                                                      @Nullable Identifier fragmentShader,
                                                      ParticlePipelineKey key) {
        var fragment = fragmentShader != null ? fragmentShader : Photon.id("core/hdr_particle");
        return INSTANCED_VARIANTS.computeIfAbsent(new InstancedKey(geometry, fragment, key), ik -> {
            var k = ik.key();
            var g = ik.geometry();
            var layout = new LayoutBuilder(SNIPPET_NAMES)
                    .sampler("Sampler0").sampler("Sampler2")
                    .uniform(PhotonMaterialUniforms.UBO_NAME);
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                    .withLocation(Photon.id("pipeline/hdr_particle_instanced_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(Photon.id("core/particle"))
                    .withFragmentShader(ik.fragmentShader())
                    .withColorTargetState(hdrTarget(k.blend()))
                    .withDepthStencilState(depthState(k))
                    .withCull(k.cull());
            applyInstancedGeometry(builder, g.layout());
            applyInstanced(builder, layout, g.variant(), g.data(), g.customData());
            applySoftParticles(builder, layout, k);
            applyWireframe(builder, layout, k);
            layout.applyTo(builder);
            return track(builder.build(), k.wireframe());
        });
    }

    /** The format of Photon's custom-mask target: one channel, a group id in 0..1. */
    public static final GpuFormat MASK_FORMAT = GpuFormat.R8_UNORM;

    private record MaskKey(@Nullable InstancedGeometryKey geometry, PrimitiveTopology mode) {
    }

    private static final Map<MaskKey, RenderPipeline> MASK_VARIANTS = new ConcurrentHashMap<>();

    /**
     * CustomMask pipeline over the material pass's geometry ({@code geometry} null = CPU-baked). Opaque, unculled
     * and depth-writing, so the mask depth doubles as a custom depth.
     */
    public static RenderPipeline mask(@Nullable InstancedGeometryKey geometry, PrimitiveTopology mode) {
        return MASK_VARIANTS.computeIfAbsent(new MaskKey(geometry, mode), mk -> {
            var layout = new LayoutBuilder(SNIPPET_NAMES)
                    .sampler("Sampler0")
                    .uniform(PhotonMaskUniforms.UBO_NAME);
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                    .withLocation(Photon.id("pipeline/mask_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(Photon.id("core/mask"))
                    .withFragmentShader(Photon.id("core/mask"))
                    .withColorTargetState(new ColorTargetState(Optional.empty(), MASK_FORMAT,
                            ColorTargetState.WRITE_ALL))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                    .withCull(false);
            if (mk.geometry() == null) {
                builder.withVertexBinding(0, PARTICLE_FORMAT).withPrimitiveTopology(mk.mode());
            } else {
                var g = mk.geometry();
                applyInstancedGeometry(builder, g.layout());
                applyInstanced(builder, layout, g.variant(), false, false);
            }
            layout.applyTo(builder);
            return builder.build();
        });
    }

    // ---- KilaGraph shader graphs (KilaGraph generates the GLSL, Photon owns the pipeline) ----------

    private record GraphKey(String contentHash, @Nullable InstancedGeometryKey geometry, ParticlePipelineKey key) {
    }

    private static final Map<GraphKey, RenderPipeline> GRAPH_VARIANTS = new ConcurrentHashMap<>();

    /** Mirrors KilaGraph's {@code RenderTypeFactory.buildPipeline}. */
    private static void applyGraphResources(LayoutBuilder layout, CompiledShaderGraph compiled) {
        for (var ubo : compiled.builtinUniforms()) {
            layout.uniform(ubo);
        }
        if (!compiled.layout().isEmpty()) {
            layout.uniform(MaterialUniformLayout.UBO_NAME);
        }
        for (var block : compiled.uniformBlocks()) {
            layout.uniform(block.uboName());
        }
        for (var sampler : compiled.layout().samplers()) {
            layout.sampler(sampler);
        }
    }

    /** {@code geometry} null = CPU-baked geometry in the emitter's topology. */
    public static RenderPipeline graphShader(CompiledShaderGraph compiled,
                                             @Nullable InstancedGeometryKey geometry,
                                             ParticlePipelineKey key) {
        return GRAPH_VARIANTS.computeIfAbsent(new GraphKey(compiled.contentHash(), geometry, key), gk -> {
            var k = gk.key();
            var shaderId = DynamicShaderSourceRegistry.shaderId(compiled.contentHash());
            var builder = RenderPipeline.builder()
                    .withLocation(Photon.id("pipeline/graph_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(shaderId)
                    .withFragmentShader(shaderId)
                    .withColorTargetState(hdrTarget(k.blend()))
                    .withDepthStencilState(depthState(k))
                    .withCull(k.cull());
            // no snippet: the generated source declares every block itself
            var layout = new LayoutBuilder(Set.of());
            applyGraphResources(layout, compiled);
            if (gk.geometry() == null) {
                builder.withVertexBinding(0, PARTICLE_FORMAT).withPrimitiveTopology(k.mode());
            } else {
                var g = gk.geometry();
                applyInstancedGeometry(builder, g.layout());
                applyInstanced(builder, layout, g.variant(), g.data(), g.customData());
            }
            layout.applyTo(builder);
            return builder.build();
        });
    }

    private record FullscreenGraphKey(String contentHash, GpuFormat format) {
    }

    private static final Map<FullscreenGraphKey, RenderPipeline> FULLSCREEN_GRAPHS = new ConcurrentHashMap<>();

    /**
     * One fullscreen-graph pass writing {@code format}. The only vanilla block it may declare is DynamicTransforms,
     * which {@code RenderGraphExecutor.dispatchGraph} binds a neutral copy of.
     */
    public static RenderPipeline fullscreenGraph(CompiledShaderGraph compiled, GpuFormat format) {
        return FULLSCREEN_GRAPHS.computeIfAbsent(new FullscreenGraphKey(compiled.contentHash(), format), fk -> {
            var shaderId = DynamicShaderSourceRegistry.shaderId(fk.contentHash());
            var builder = PhotonFullscreenPass.builder()
                    .withLocation(Photon.id("pipeline/postfx_graph_" + VARIANT_ID.getAndIncrement()))
                    .withVertexShader(shaderId)
                    .withFragmentShader(shaderId)
                    .withColorTargetState(new ColorTargetState(Optional.empty(), fk.format(),
                            ColorTargetState.WRITE_ALL));
            var layout = new LayoutBuilder(Set.of());
            applyGraphResources(layout, compiled);
            layout.applyTo(builder);
            return builder.build();
        });
    }

    public static void register(RegisterRenderPipelinesEvent event) {
        // precompile the common case; a registered pipeline that fails to compile crashes resource load
        event.registerPipeline(hdrParticle(ParticlePipelineKey.DEFAULT));
    }

    // ---- custom user shaders (user vsh + fsh, the 1.21 JSON is the metadata) ----------------------

    /** Only what affects compilation, so uniform edits and texture swaps never recompile. */
    public record CustomShaderKey(Identifier vertexShader,
                                  Identifier fragmentShader,
                                  Map<String, Float> defines,
                                  List<String> samplerNames,
                                  List<String> sceneSamplers,
                                  ParticlePipelineKey pipelineKey) {
    }

    private static LayoutBuilder customShaderLayout(CustomShaderKey key) {
        var layout = new LayoutBuilder(SNIPPET_NAMES)
                .sampler("Sampler0").sampler("Sampler2")
                .uniform(PhotonEngineUniforms.UBO_NAME)
                .uniform(PhotonCustomUniforms.UBO_NAME)
                .uniform(PhotonMaterialUniforms.UBO_NAME);
        key.samplerNames().forEach(layout::sampler);
        key.sceneSamplers().forEach(layout::sampler);
        return layout;
    }

    /** The user's stages adapted to this geometry, under the defines the pipeline carries. */
    private static PhotonShaderSources.Adapted adaptCustomSources(CustomShaderKey key, int providedLocations,
                                                                  Set<Integer> tailLocations,
                                                                  Collection<String> extraDefines) {
        var defines = new HashSet<>(key.defines().keySet());
        defines.addAll(extraDefines);
        return PhotonShaderSources.adapt(key.vertexShader(), key.fragmentShader(), providedLocations, tailLocations,
                defines);
    }

    private static void applyCustomState(RenderPipeline.Builder builder, CustomShaderKey key,
                                         PhotonShaderSources.Adapted sources) {
        var pk = key.pipelineKey();
        builder.withVertexShader(sources.vertex())
                .withFragmentShader(sources.fragment())
                .withColorTargetState(hdrTarget(pk.blend()))
                .withDepthStencilState(depthState(pk))
                .withCull(pk.cull());
        key.defines().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> builder.withShaderDefine(e.getKey(), e.getValue()));
    }

    private static final Map<CustomShaderKey, RenderPipeline> CUSTOM_SHADER_VARIANTS = new ConcurrentHashMap<>();

    public static RenderPipeline customShader(CustomShaderKey key) {
        return CUSTOM_SHADER_VARIANTS.computeIfAbsent(key, k -> {
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                    .withLocation(Photon.id("pipeline/custom_shader_" + VARIANT_ID.getAndIncrement()))
                    .withVertexBinding(0, PARTICLE_FORMAT)
                    .withPrimitiveTopology(k.pipelineKey().mode());
            // no attribute tail on CPU geometry: tail inputs read the GL default, as on 26.1
            applyCustomState(builder, k, adaptCustomSources(k, PARTICLE_FORMAT.getElements().size(), Set.of(),
                    List.of()));
            customShaderLayout(k).applyTo(builder);
            return builder.build();
        });
    }

    private record InstancedCustomKey(InstancedGeometryKey geometry, CustomShaderKey key) {
    }

    private static final Map<InstancedCustomKey, RenderPipeline> INSTANCED_CUSTOM_VARIANTS = new ConcurrentHashMap<>();

    /** Custom pipelines are built from adapted source text, so they must be rebuilt after a reload. */
    public static void onResourceReload() {
        CUSTOM_SHADER_VARIANTS.clear();
        INSTANCED_CUSTOM_VARIANTS.clear();
    }

    public static RenderPipeline instancedCustomShader(InstancedGeometryKey geometry, CustomShaderKey key) {
        return INSTANCED_CUSTOM_VARIANTS.computeIfAbsent(new InstancedCustomKey(geometry, key), ik -> {
            var k = ik.key();
            var g = ik.geometry();
            var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                    .withLocation(Photon.id("pipeline/custom_instanced_" + VARIANT_ID.getAndIncrement()));
            // the defines applyInstanced adds, so the adapter evaluates the same branches
            var defines = new ArrayList<>(g.variant().defines);
            if (g.variant().usesVat()) defines.add(DATA_DEFINE);
            var sources = adaptCustomSources(k, g.layout().elementCount(), g.layout().tailLocations(), defines);
            applyInstancedGeometry(builder, g.layout(), sources.tailInputs());
            applyCustomState(builder, k, sources);
            var layout = customShaderLayout(k);
            // custom shaders read the attribute tail, not the records
            applyInstanced(builder, layout, g.variant(), false, false);
            layout.applyTo(builder);
            return builder.build();
        });
    }
}
