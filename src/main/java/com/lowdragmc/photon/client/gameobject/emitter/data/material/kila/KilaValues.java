package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.TreeSet;

/**
 * The values of one variant's {@link KilaBlock} for one material: the modules write them by name, {@link #upload}
 * sends the block when it differs from what the GPU already holds.
 * <p>
 * Values take the member's type (an {@code ivec4} truncates floats). A name the block lacks — written but never
 * declared — is dropped and reported once.
 */
public final class KilaValues implements AutoCloseable {
    private final KilaBlock block;
    // direct: the encoder hands it to GL as is. GC-owned, so a material dropped without close() leaks no native memory
    private final ByteBuffer staged;
    private final ByteBuffer uploaded;
    private final String label;
    @Nullable
    private GpuBuffer buffer;
    private boolean closed;
    @Nullable
    private Set<String> undeclared;

    public KilaValues(String label, KilaBlock block) {
        this.label = label;
        this.block = block;
        this.staged = ByteBuffer.allocateDirect(block.size()).order(ByteOrder.nativeOrder());
        this.uploaded = ByteBuffer.allocateDirect(block.size()).order(ByteOrder.nativeOrder());
    }

    /** Every member back to 0. */
    public void clear() {
        for (int i = 0; i < staged.capacity(); i += 4) staged.putInt(i, 0);
    }

    public void set(String name, float x, float y, float z, float w) {
        var field = field(name);
        if (field == null) return;
        put(field, 0, x);
        put(field, 1, y);
        put(field, 2, z);
        put(field, 3, w);
    }

    public void set(String name, int x, int y, int z, int w) {
        var field = field(name);
        if (field == null) return;
        put(field, 0, x);
        put(field, 1, y);
        put(field, 2, z);
        put(field, 3, w);
    }

    public void set(String name, float x) {
        var field = field(name);
        if (field != null) put(field, 0, x);
    }

    /** As many components as {@code values} has, from the first. */
    public void set(String name, float[] values) {
        var field = field(name);
        if (field == null) return;
        for (int i = 0; i < values.length; i++) put(field, i, values[i]);
    }

    @Nullable
    private KilaBlock.Field field(String name) {
        var field = block.field(name);
        if (field == null) {
            if (undeclared == null) undeclared = new TreeSet<>();
            if (undeclared.add(name)) {
                Photon.LOGGER.warn("{}: {} is written but no module of this variant declared it", label, name);
            }
        }
        return field;
    }

    private void put(KilaBlock.Field field, int component, float value) {
        if (component >= field.type().components()) return;
        int at = field.offset() + component * 4;
        if (field.type().isInt()) {
            staged.putInt(at, (int) value);
        } else {
            staged.putFloat(at, value);
        }
    }

    private void put(KilaBlock.Field field, int component, int value) {
        if (component >= field.type().components()) return;
        int at = field.offset() + component * 4;
        if (field.type().isInt()) {
            staged.putInt(at, value);
        } else {
            staged.putFloat(at, value);
        }
    }

    /** The names written that the block does not carry, in order. */
    public Set<String> undeclared() {
        return undeclared == null ? Set.of() : undeclared;
    }

    /** Sends what was staged, when it changed. Render thread, outside any render pass. */
    public void upload() {
        if (closed) return;
        RenderSystem.assertOnRenderThread();
        boolean fresh = buffer == null;
        if (fresh) {
            buffer = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    block.size());
        }
        if (!fresh && staged.mismatch(uploaded) == -1) return;
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), staged.duplicate().clear());
        uploaded.clear();
        uploaded.put(staged.duplicate().clear());
        uploaded.clear();
    }

    /** What to bind as {@link KilaBlock#NAME}; null before the first {@link #upload} and once closed. */
    @Nullable
    public GpuBufferSlice slice() {
        return closed || buffer == null ? null : buffer.slice();
    }

    /** Frees the GPU buffer. Render thread. */
    @Override
    public void close() {
        closed = true;
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
