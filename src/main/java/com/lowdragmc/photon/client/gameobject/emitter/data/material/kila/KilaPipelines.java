package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.lowdragmc.photon.client.render.StackedDistortion;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The KilaMaterial programs: one pipeline per (layout, emitter state, geometry), shared by every material with that
 * layout (each binds its own values of the variant's {@link KilaBlock}). A pipeline lives while a {@link Lease} holds
 * it; the last one out {@linkplain PhotonPipelines#release releases} its program, which 26.1 would otherwise keep
 * until the next resource reload.
 */
public final class KilaPipelines {
    public static final String DATA_DEFINE = "KILA_DATA";
    public static final String CUSTOM_DATA_DEFINE = "KILA_CUSTOM_DATA";
    /** A stacked screen distortion adds into the offsets: the factors here, the MAX on alpha around the draw. */
    static final BlendFunction STACK_BLEND = new BlendFunction(SourceFactor.ONE, DestFactor.ONE, SourceFactor.ONE,
            DestFactor.ONE);

    private record Key(String layout, PhotonPipelines.ParticlePipelineKey state,
                       @Nullable PhotonPipelines.InstancedVariant variant, boolean cpuData) {
    }

    private static final class Held {
        final RenderPipeline pipeline;
        int leases;

        Held(RenderPipeline pipeline) {
            this.pipeline = pipeline;
        }
    }

    /**
     * What one user — a material's RenderType, a test — holds. Closing it lets go of every pipeline it was handed;
     * one nobody else holds is released. Render thread.
     */
    public static final class Lease implements AutoCloseable {
        private final Set<Key> keys = new HashSet<>();
        private boolean closed;

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            for (var key : keys) {
                var held = PIPELINES.get(key);
                if (held != null && --held.leases <= 0) {
                    PIPELINES.remove(key);
                    PhotonPipelines.release(held.pipeline);
                }
            }
            keys.clear();
        }
    }

    private static final Map<Key, Held> PIPELINES = new HashMap<>();
    private static final Set<Key> FAILED = new HashSet<>();
    private static final AtomicInteger ID = new AtomicInteger();
    private static int failedGeneration = -1;

    private KilaPipelines() {
    }

    /**
     * The pipeline for {@code layout} under {@code state}, held by {@code lease}; {@code variant} null is the CPU
     * geometry, in the state's mode, with the per-particle records bound when {@code cpuData} asks. Null when it fails
     * to compile (logged once), or when the lease was already closed. Render thread.
     */
    @Nullable
    public static RenderPipeline get(Lease lease, KilaLayout layout, PhotonPipelines.ParticlePipelineKey state,
                                     @Nullable PhotonPipelines.InstancedVariant variant, boolean cpuData) {
        RenderSystem.assertOnRenderThread();
        // a RenderType outliving its lease must not hold a pipeline again, with nothing left to let it go
        if (lease.closed) return null;
        // a resource reload brings fresh sources: let the broken ones try again
        int generation = PhotonRenderTypes.reloadGeneration();
        if (generation != failedGeneration) {
            failedGeneration = generation;
            FAILED.clear();
        }
        var key = new Key(layout.key, state, variant, cpuData && variant == null);
        if (FAILED.contains(key)) return null;
        var held = PIPELINES.computeIfAbsent(key, k -> new Held(build(layout, k)));
        if (lease.keys.add(key)) held.leases++;
        if (!RenderSystem.getDevice().precompilePipeline(held.pipeline).isValid()) {
            if (FAILED.add(key)) Photon.LOGGER.error("Failed to compile KilaMaterial variant {} ({}, {})",
                    describe(layout), variant == null ? state.mode() : variant, key.cpuData() ? "cpu data" : "plain");
            return null;
        }
        return held.pipeline;
    }

    /** Pipelines some lease holds. */
    public static int heldCount() {
        return PIPELINES.size();
    }

    /** The layout's defines without the block's member list, which runs to kilobytes. */
    private static String describe(KilaLayout layout) {
        var defines = new StringJoiner("|");
        for (var define : layout.defines) {
            if (!define.startsWith(KilaBlock.MEMBERS_DEFINE + " ")) defines.add(define);
        }
        return defines.toString();
    }

    private static RenderPipeline build(KilaLayout layout, Key key) {
        var state = key.state();
        var builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(Photon.id("pipeline/kila_" + ID.getAndIncrement()))
                .withVertexShader(Photon.id("core/kila"))
                .withFragmentShader(Photon.id("core/kila"))
                .withUniform("PhotonEngine", UniformType.UNIFORM_BUFFER)
                .withUniform(KilaBlock.NAME, UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler2");
        layout.defines.forEach(builder::withShaderDefine);
        for (var texture : layout.textures) builder.withSampler(texture.name());
        for (var scene : layout.sceneSamplers) builder.withSampler(scene);
        // the depth captures read through soft_particle.glsl's photon_eye_from_ndcz, which only PHOTON_SOFT compiles in
        if (layout.readsSceneDepth()) builder.withShaderDefine(PhotonPipelines.SOFT_PARTICLE_DEFINE);
        if (layout.lit) PhotonPipelines.declareLights(builder);
        PhotonPipelines.applyState(builder, state);
        PhotonPipelines.applyGeometry(builder, key.variant(), state.mode(), layout.defines.contains(DATA_DEFINE),
                layout.defines.contains(CUSTOM_DATA_DEFINE), key.cpuData());
        var pipeline = builder.build();
        if (layout.defines.contains(KilaScreenDistortion.STACK_DEFINE)) StackedDistortion.mark(pipeline);
        return layout.lit ? PhotonPipelines.markLit(pipeline) : pipeline;
    }

    /** {@code state} as a stacked distortion draws it: adding into the offsets, tested against the scene, never written. */
    static PhotonPipelines.ParticlePipelineKey stacked(PhotonPipelines.ParticlePipelineKey state) {
        return new PhotonPipelines.ParticlePipelineKey(STACK_BLEND, PhotonPipelines.BLEND_EQUATION_ADD, state.cull(),
                state.depthTest(), false, state.mode(), false, false, false);
    }

    /** {@code state} with its depth test off: a projection paints the very terrain that stands in front of its quad. */
    static PhotonPipelines.ParticlePipelineKey untested(PhotonPipelines.ParticlePipelineKey state) {
        return new PhotonPipelines.ParticlePipelineKey(state.blend(), state.blendEquation(), state.cull(), false,
                state.depthMask(), state.mode(), state.wireframe(), state.softParticles(), state.litParticles());
    }

    /** What a thumbnail draws with: the material's blend, or a straight one, facing the camera, nothing to test. */
    static PhotonPipelines.ParticlePipelineKey preview(@Nullable BlendFunction blend, int equation, VertexFormat.Mode mode) {
        return new PhotonPipelines.ParticlePipelineKey(blend, equation, false, false, false, mode, false, false, false);
    }
}
