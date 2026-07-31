package com.lowdragmc.photon.client.postfx.runtime;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code PhotonPass} std140 block of ONE hand-written pass shader dispatch — the 26.1 replacement
 * for setting loose uniforms on a {@code ShaderInstance}.
 *
 * <p>The member order is the shader JSON's {@code uniforms} declaration order ({@link
 * CustomShaderPass.Info#uniforms()}), and the {@code .fsh} declares its block in that same order. The
 * JSON is therefore the single source of truth for both sides — a pass shader can be added or edited
 * with no Java change, and {@code PostFXShaderAssetsTest} fails the build if the two ever drift.</p>
 *
 * <p>Values are staged per dispatch and uploaded before the pass opens ({@code writeToBuffer} is illegal
 * inside one). One instance is cached per pass shader and reused across frames — dispatches are
 * sequential on the render thread, and every dispatch re-stages the full layout from the JSON defaults
 * first, so nothing leaks between them.</p>
 */
public final class PassUniforms implements AutoCloseable {

    /** The block name every pass shader declares, and the pipeline's uniform name. */
    public static final String BLOCK_NAME = "PhotonPass";

    private final List<CustomShaderPass.UniformSpec> layout;
    private final Map<String, float[]> values = new HashMap<>();
    private final int byteSize;

    @Nullable
    private GpuBuffer buffer;
    /** Staging bytes, sized once — {@code byteSize} never changes, so a per-upload malloc/free pair
     *  would be pure waste on a path that runs several times a frame. */
    @Nullable
    private ByteBuffer staging;
    private boolean closed;

    public PassUniforms(List<CustomShaderPass.UniformSpec> layout) {
        this.layout = List.copyOf(layout);
        this.byteSize = std140Size(this.layout);
    }

    /** std140: float 4B@4, vec2 8B@8, vec3 12B@16, vec4 16B@16; the block is padded to 16. */
    private static int std140Size(List<CustomShaderPass.UniformSpec> layout) {
        var offset = 0;
        for (var spec : layout) {
            var align = alignmentOf(spec.count());
            offset = (offset + align - 1) / align * align + spec.count() * 4;
        }
        return Math.max(16, (offset + 15) / 16 * 16);
    }

    private static int alignmentOf(int count) {
        return switch (count) {
            case 1 -> 4;
            case 2 -> 8;
            default -> 16;
        };
    }

    /** The declared component count of {@code name} (0 when the shader has no such member) — what
     *  disambiguates an ARGB color from a plain number when a value arrives as an Integer. */
    public int countOf(String name) {
        for (var spec : layout) {
            if (spec.name().equals(name)) return spec.count();
        }
        return 0;
    }

    /** Reset every member to its JSON default — the start of each dispatch, so a value a previous
     *  dispatch set (or an absent {@code ParamRef}) can never bleed into this one. */
    public void resetToDefaults() {
        values.clear();
        for (var spec : layout) {
            values.put(spec.name(), spec.defaults());
        }
    }

    /** Stage one member's components; missing tail components stay 0. Unknown names are ignored —
     *  a pass may bind a param the shader does not declare (an author renamed a uniform). */
    public void set(String name, float... components) {
        if (values.containsKey(name)) {
            values.put(name, components);
        }
    }

    /** Upload the staged block. Must run OUTSIDE an open render pass, on the render thread. */
    public void upload() {
        if (closed || layout.isEmpty()) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        if (buffer == null) {
            buffer = RenderSystem.getDevice().createBuffer(() -> "PhotonPass UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, byteSize);
        }
        if (staging == null) {
            // calloc: std140 padding bytes are uploaded along with everything else and never written
            staging = MemoryUtil.memCalloc(byteSize);
        }
        var bytes = staging;
        bytes.clear();
        {
            var builder = Std140Builder.intoBuffer(bytes);
            for (var spec : layout) {
                var v = values.getOrDefault(spec.name(), spec.defaults());
                switch (spec.count()) {
                    case 1 -> builder.putFloat(at(v, 0));
                    case 2 -> builder.putVec2(at(v, 0), at(v, 1));
                    // NOT putVec3: it advances a full 16 bytes, but std140 gives a vec3 a base SIZE of
                    // 12 (only its alignment is 16), so a scalar declared after one packs into that tail
                    // — writing 16 would put every following member 4 bytes past where GLSL reads it
                    case 3 -> {
                        builder.align(16);
                        builder.putFloat(at(v, 0));
                        builder.putFloat(at(v, 1));
                        builder.putFloat(at(v, 2));
                    }
                    default -> builder.putVec4(at(v, 0), at(v, 1), at(v, 2), at(v, 3));
                }
            }
            bytes.position(byteSize).rewind();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bytes);
        }
    }

    /** Bind the staged block, if this pass has one. The block NAME lives here rather than at each
     *  dispatch site — three of them would otherwise repeat the string literal. */
    public void bindTo(RenderPass renderPass) {
        if (!closed && buffer != null) {
            renderPass.setUniform(BLOCK_NAME, buffer.slice());
        }
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
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
    }
}
