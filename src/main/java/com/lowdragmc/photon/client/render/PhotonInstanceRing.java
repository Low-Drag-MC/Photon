package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;

/**
 * Per-emitter ring buffer for instanced draw data, bound as a {@code TEXEL_BUFFER} uniform
 * (RGBA8 texels; the shader bit-reassembles floats — see {@code photon:particle.glsl}). Texel
 * uniforms must bind the ENTIRE buffer, so this cannot be a slice of a shared ring: each use site
 * owns one. Triple-buffered + fenced like the vertex ring ({@link PhotonBufferCache}); the drain
 * registers used rings and {@link #rotate()}s them at the frame boundary.
 */
public final class PhotonInstanceRing implements AutoCloseable {

    private static final int USAGE = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE;

    @Nullable
    private MappableRingBuffer buffer;

    /** Copy the staging floats (position → limit) into this frame's buffer and return it (whole
     *  buffer — capacity may exceed the written range; fetches are bounded by instance count). */
    public GpuBuffer write(FloatBuffer staging) {
        var bytes = staging.remaining() * Float.BYTES;
        if (buffer == null || buffer.size() < bytes) {
            if (buffer != null) {
                buffer.close();
            }
            // 1.5x headroom: growing trails/bursts would otherwise reallocate every frame
            var capacity = buffer == null ? bytes : Math.max(bytes, buffer.size() + (buffer.size() >> 1));
            buffer = new MappableRingBuffer(() -> "Photon instance data", USAGE, capacity);
        }
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(buffer.currentBuffer().slice(0, bytes), false, true)) {
            view.data().asFloatBuffer().put(staging);
        }
        return buffer.currentBuffer();
    }

    public void rotate() {
        if (buffer != null) {
            buffer.rotate();
        }
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
