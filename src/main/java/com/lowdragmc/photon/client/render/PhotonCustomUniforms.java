package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The live {@code PhotonCustomMaterial} std140 block for ONE custom-shader material instance —
 * the 1.21 dynamic-uniform capability rebuilt on 26.1 (mirrors KilaGraph's
 * {@code MaterialUniformBuffer}): {@link #set} marks dirty, {@link #prepareUpload()} re-uploads
 * OUTSIDE any render pass, {@link #slice()} is bound at draw. Changing a value never recompiles
 * anything. Member order is SORTED BY NAME — the converter emits the GLSL block in the same order
 * (block members are global scope, so user code reads them unchanged).
 */
public final class PhotonCustomUniforms implements AutoCloseable {

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
    private final Map<String, float[]> values = new HashMap<>();
    private final int byteSize;

    @Nullable
    private GpuBuffer buffer;
    private boolean dirty = true;
    private boolean closed;

    public PhotonCustomUniforms(List<Field> fields) {
        this.fields = List.copyOf(fields);
        this.byteSize = std140Size(this.fields);
    }

    /** std140: scalars 4B@4, vec2 8B@8, vec3/vec4 16B@16, mat4 64B@16; block padded to 16. */
    private static int std140Size(List<Field> fields) {
        var offset = 0;
        for (var field : fields) {
            var size = switch (field.type()) {
                case FLOAT, INT -> 4;
                case VEC2 -> 8;
                case VEC3, VEC4 -> 16;
                case MAT4 -> 64;
            };
            var align = switch (field.type()) {
                case FLOAT, INT -> 4;
                case VEC2 -> 8;
                default -> 16;
            };
            offset = (offset + align - 1) / align * align + size;
        }
        return Math.max(16, (offset + 15) / 16 * 16);
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
        ByteBuffer bytes = MemoryUtil.memAlloc(byteSize);
        try {
            var builder = Std140Builder.intoBuffer(bytes);
            for (var field : fields) {
                var v = values.getOrDefault(field.name(), new float[0]);
                switch (field.type()) {
                    case FLOAT -> builder.putFloat(at(v, 0));
                    case INT -> builder.putInt((int) at(v, 0));
                    case VEC2 -> builder.putVec2(at(v, 0), at(v, 1));
                    case VEC3 -> builder.putVec3(at(v, 0), at(v, 1), at(v, 2));
                    case VEC4 -> builder.putVec4(at(v, 0), at(v, 1), at(v, 2), at(v, 3));
                    case MAT4 -> {
                        var m = new float[16];
                        for (int i = 0; i < 16; i++) {
                            m[i] = at(v, i);
                        }
                        builder.putMat4f(new Matrix4f().set(m));
                    }
                }
            }
            bytes.rewind();
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

    // ---- RenderType association (vanilla-phase draws bind via RenderTypeMixin) -------------------

    private static final Map<net.minecraft.client.renderer.rendertype.RenderType, PhotonCustomUniforms>
            BY_RENDER_TYPE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void register(net.minecraft.client.renderer.rendertype.RenderType renderType,
                                PhotonCustomUniforms uniforms) {
        BY_RENDER_TYPE.put(renderType, uniforms);
    }

    /** Drop a dead RenderType's association (shader invalidation — prevents registry leaks). */
    public static void unregister(net.minecraft.client.renderer.rendertype.RenderType renderType) {
        BY_RENDER_TYPE.remove(renderType);
    }

    /** The slice to bind as {@code PhotonCustomMaterial} for this RenderType's draw, or null. */
    @Nullable
    public static GpuBufferSlice sliceFor(net.minecraft.client.renderer.rendertype.RenderType renderType) {
        var uniforms = BY_RENDER_TYPE.get(renderType);
        return uniforms == null ? null : uniforms.slice();
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
