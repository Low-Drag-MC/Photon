package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * The live {@code PhotonCustomMaterial} std140 block for ONE custom-shader material instance —
 * the 1.21 dynamic-uniform capability rebuilt on 26.1 (mirrors KilaGraph's
 * {@code MaterialUniformBuffer}): {@link #set} marks dirty, {@link #prepareUpload()} re-uploads
 * OUTSIDE any render pass, {@link #slice()} is bound at draw. Changing a value never recompiles
 * anything. Member order is SORTED BY NAME — the converter emits the GLSL block in the same order
 * (block members are global scope, so user code reads them unchanged).
 */
public final class PhotonCustomUniforms implements AutoCloseable {

    public static final String UBO_NAME = "PhotonCustomMaterial";

    /** GLSL types a custom material uniform may have (std140 scalar/vector/matrix subset). */
    public enum Type {
        FLOAT(1), INT(1), VEC2(2), VEC3(3), VEC4(4), MAT4(16);

        public final int components;

        Type(int components) {
            this.components = components;
        }

        @Nullable
        public static Type parse(String glslType) {
            return switch (glslType.toLowerCase(Locale.ROOT)) {
                case "float" -> FLOAT;
                case "int" -> INT;
                case "vec2" -> VEC2;
                case "vec3" -> VEC3;
                case "vec4" -> VEC4;
                case "mat4" -> MAT4;
                default -> null;
            };
        }
    }

    public record Field(String name, Type type) {
    }

    /** Name-sorted fields (MUST mirror the converter's block emission order). */
    private final List<Field> fields;
    /** Each field's std140 byte offset, parallel to {@link #fields}. */
    private final int[] offsets;
    private final Map<String, float[]> values = new HashMap<>();
    private final int byteSize;

    @Nullable
    private GpuBuffer buffer;
    private boolean dirty = true;
    private boolean closed;

    public PhotonCustomUniforms(List<Field> fields) {
        this.fields = List.copyOf(fields);
        this.offsets = new int[this.fields.size()];
        var end = 0;
        for (int i = 0; i < this.fields.size(); i++) {
            var type = this.fields.get(i).type();
            offsets[i] = align(end, alignment(type));
            end = offsets[i] + size(type);
        }
        this.byteSize = Math.max(16, align(end, 16));
    }

    /** std140: a scalar after a vec3 packs into its fourth component, so offsets are computed, not built. */
    private static int size(Type type) {
        return switch (type) {
            case FLOAT, INT -> 4;
            case VEC2 -> 8;
            case VEC3 -> 12;
            case VEC4 -> 16;
            case MAT4 -> 64;
        };
    }

    private static int alignment(Type type) {
        return switch (type) {
            case FLOAT, INT -> 4;
            case VEC2 -> 8;
            case VEC3, VEC4, MAT4 -> 16;
        };
    }

    private static int align(int offset, int alignment) {
        return (offset + alignment - 1) / alignment * alignment;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public List<Field> fields() {
        return fields;
    }

    /** Set a field's components; missing tail components stay 0. Cheap — upload is deferred. */
    public void set(String name, float... components) {
        values.put(name, components.clone());
        dirty = true;
    }

    /** (Re)upload when dirty. Must run OUTSIDE an open render pass, on the render thread.
     *  Empty layouts still allocate a minimal zeroed block: custom pipelines declare
     *  PhotonCustomMaterial unconditionally and draw validation requires a bound value. */
    public void prepareUpload() {
        if (closed) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        if (buffer == null) {
            buffer = RenderSystem.getDevice().createBuffer(
                    () -> "PhotonCustomMaterial UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    byteSize);
            dirty = true;
        }
        if (!dirty) {
            return;
        }
        // calloc: padding is uploaded too
        ByteBuffer bytes = MemoryUtil.memCalloc(byteSize);
        try {
            for (int f = 0; f < fields.size(); f++) {
                var field = fields.get(f);
                var v = values.getOrDefault(field.name(), new float[0]);
                var offset = offsets[f];
                if (field.type() == Type.INT) {
                    bytes.putInt(offset, (int) at(v, 0));
                } else {
                    // column-major for mat4, which is also how 1.21 JSON listed matrix values
                    for (int i = 0; i < field.type().components; i++) {
                        bytes.putFloat(offset + i * Float.BYTES, at(v, i));
                    }
                }
            }
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bytes);
            dirty = false;
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    /** The slice to bind as {@code PhotonCustomMaterial}; null only before the first upload. */
    @Nullable
    public GpuBufferSlice slice() {
        if (closed || buffer == null) {
            return null;
        }
        return buffer.slice();
    }

    private static float at(float[] v, int i) {
        return i < v.length ? v[i] : 0f;
    }

    @Override
    public void close() {
        closed = true;
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
