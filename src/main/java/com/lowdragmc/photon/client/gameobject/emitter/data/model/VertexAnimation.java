package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.Cleaner;

/**
 * A baked pose table on the GPU: the texel buffer the model path samples when every particle is at its
 * own frame of the animation.
 *
 * <p>Uploaded once and then read-only, so a swarm costs one buffer and no per-frame work at all.</p>
 *
 * <p>An RGBA32F texel buffer bound by name, like {@code PhotonPoints}.</p>
 */
public final class VertexAnimation {

    private static final class Resource implements AutoCloseable {
        @Nullable
        private GpuBuffer buffer;

        @Override
        public void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
        }
    }

    private final float[] table;
    private final int vertexCount;
    private final int frames;
    /** (shared clip position, random weight, t weight, flags: +1 blend, +2 clamp) — MIRRORED FROM the
     *  PHOTON_VAT block of particle.glsl. */
    private final float[] phase = new float[4];
    @Nullable
    private Resource resource;
    @Nullable
    private Cleaner.Cleanable cleanable;

    public VertexAnimation(float[] table, int vertexCount, int frames) {
        this.table = table;
        this.vertexCount = vertexCount;
        this.frames = frames;
    }

    /** The baked poses, for the CPU draw path — the instanced one reads {@link #buffer()} instead. */
    public float[] table() {
        return table;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** In intervals: the table holds one more row than this, the clip's end. */
    public int frames() {
        return frames;
    }

    public float[] phase() {
        return phase;
    }

    public void setPhase(float base, float randomWeight, float tWeight, boolean interpolate, boolean clamp) {
        phase[0] = base;
        phase[1] = randomWeight;
        phase[2] = tWeight;
        phase[3] = (interpolate ? 1f : 0f) + (clamp ? 2f : 0f);
    }

    /** Whether adjacent baked frames are blended rather than snapped between. */
    public boolean interpolates() {
        return phase[3] % 2f > 0.5f;
    }

    public boolean clamps() {
        return phase[3] > 1.5f;
    }

    /** The texel buffer the {@code PHOTON_VAT} vertex stage fetches from, created on first use.
     *  Render thread only, and outside an open render pass — buffer creation is illegal inside one. */
    @Nullable
    public GpuBuffer buffer() {
        if (table.length == 0) {
            return null;
        }
        if (resource == null) {
            RenderSystem.assertOnRenderThread();
            resource = new Resource();
            cleanable = AutoCloseCleaner.registerRenderThread(this, resource);
            // the table can be tens of megabytes, so the staging copy is freed rather than left to the GC
            var staging = MemoryUtil.memAlloc(table.length * Float.BYTES);
            try {
                for (var value : table) {
                    staging.putFloat(value);
                }
                staging.flip();
                resource.buffer = RenderSystem.getDevice().createBuffer(
                        () -> "Photon vertex animation table",
                        GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, staging);
            } finally {
                MemoryUtil.memFree(staging);
            }
        }
        return resource.buffer;
    }

    public void dispose() {
        if (cleanable != null) {
            cleanable.clean();
            cleanable = null;
        }
        resource = null;
    }
}
