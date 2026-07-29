package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import org.lwjgl.opengl.ARBVertexAttribBinding;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The 1.21 divisor-attribute transport on 26.1 (approved plan C1): the engine's vertex system has no
 * per-instance attribute concept, so {@code VertexArrayCacheMixin} applies these layouts right after
 * the engine binds the VAO of one of Photon's dedicated instanced vertex formats. Layouts are the
 * 1.21 {@code defineInstanceAttributes} tables verbatim; {@code particle.glsl}'s
 * {@code layout(location = N)} declarations pin the matching locations. Active only between
 * {@link #begin}/{@link #end} around Photon's own instanced {@code drawIndexed}. Render thread only.
 * <p>
 * <b>The VAO is NOT exclusively ours.</b> The engine re-specifies a VAO's attributes whenever its
 * vertex buffer changes, and under ARB_vertex_attrib_binding it routes them all to binding 0 — where
 * the divisor lives on the BINDING, not the attribute. Sharing binding 0 therefore let an unrelated
 * emitter's draw silently turn our per-instance stepping into per-vertex stepping, which exploded
 * instanced geometry until the client restarted. So we speak whichever model the engine speaks
 * ({@link #useSeparateBindings()}) and, in the separate model, keep our streams on our own binding
 * points, re-asserted every draw.
 */
public final class PhotonInstancedDrawState {

    /** One divisor/base attribute: location, component count, integer pointer?, float offset. */
    public record Attrib(int location, int size, boolean integer, int offsetFloats) {
    }

    /**
     * @param strideFloats  floats per instance record
     * @param attribs       divisor-1 instance attributes (1.21 locations)
     * @param baseAttribs   base-mesh attribute overrides, or null when the pipeline format's own
     *                      POSITION pointer already matches (tile: 3-float quad)
     * @param baseStride    floats per base vertex (for the overrides)
     */
    public record Layout(int strideFloats, List<Attrib> attribs,
                         @Nullable List<Attrib> baseAttribs, int baseStride) {
        /**
         * This layout plus a per-instance attribute tail — the emitter's {@code AdditionalGPUDataSetting}
         * channels and custom-data streams, which hand-written custom shaders read as extra
         * {@code layout(location = N) in} declarations. The tail lives in the SAME instance record, so the
         * stride grows by its floats; offsets come from {@code AdditionalGPUDataSetting.planAttribs}.
         */
        public Layout withInstanceTail(List<Attrib> tail, int tailFloats) {
            if (tail.isEmpty()) {
                return this;
            }
            var all = new java.util.ArrayList<>(attribs);
            all.addAll(tail);
            return new Layout(strideFloats + tailFloats, List.copyOf(all), baseAttribs, baseStride);
        }
    }

    private static Attrib f(int location, int size, int offset) {
        return new Attrib(location, size, false, offset);
    }

    private static Attrib i(int location, int size, int offset) {
        return new Attrib(location, size, true, offset);
    }

    // 1.21 defineInstanceAttributes tables — MIRRORED; keep in lockstep with particle.glsl
    public static final Layout TILE = new Layout(21,
            List.of(f(1, 3, 0), f(2, 2, 3), f(3, 3, 5), f(4, 4, 8), f(5, 4, 12), f(6, 4, 16), i(7, 1, 20)),
            null, 3);
    public static final Layout MODEL = new Layout(15,
            List.of(f(4, 3, 0), f(5, 3, 3), f(6, 4, 6), f(7, 4, 10), i(8, 1, 14)),
            List.of(f(0, 3, 0), f(1, 2, 3), f(2, 3, 5), f(3, 1, 8)), 9);
    public static final Layout TRAIL = new Layout(4,
            List.of(i(1, 2, 0), f(2, 2, 2)),
            List.of(f(0, 2, 0)), 2);
    public static final Layout ARA = new Layout(3,
            List.of(i(1, 1, 0), f(2, 2, 1)),
            List.of(f(0, 2, 0)), 2);
    /** Tube ara-trails: the ring pair is the whole base mesh, so an instance is just its point index. */
    public static final Layout ARA_TUBE = new Layout(1,
            List.of(i(1, 1, 0)),
            List.of(f(0, 4, 0)), 4);
    public static final Layout BEAM = new Layout(16,
            List.of(f(1, 4, 0), f(2, 3, 4), f(3, 4, 7), f(4, 4, 11), i(5, 1, 15)),
            List.of(f(0, 2, 0)), 2);

    @Nullable
    private static Layout activeLayout;
    private static int instanceVbo;
    private static int baseVbo;
    /** C2: while true, {@code GlConstMixin} maps declared-RGBA8 texel buffers to GL_RGBA32F
     *  (PhotonPoints/PhotonData carry raw floats — the 1.21 samplerBuffer contract). */
    public static boolean active;

    private PhotonInstancedDrawState() {
    }

    public static void begin(Layout layout, GpuBuffer instances, GpuBuffer base) {
        activeLayout = layout;
        instanceVbo = ((GlBuffer) instances).handle;
        baseVbo = ((GlBuffer) base).handle;
        active = true;
    }

    public static void end() {
        activeLayout = null;
        active = false;
    }

    /**
     * Binding points for our two streams. 26.1's engine routes EVERY attribute of a format to binding
     * <b>0</b> ({@code VertexArrayCache$Separate.setupCombinedAttributes}), so ours must live elsewhere:
     * in the separate model a divisor belongs to the BINDING, and sharing binding 0 with the engine
     * means the next time it re-points attributes there (it does that whenever a VAO's vertex buffer
     * changes — i.e. as soon as another emitter draws) our per-instance stepping silently becomes
     * per-vertex. That is what made instanced geometry explode after an unrelated fx rendered, and
     * never recover.
     */
    private static final int BINDING_BASE = 1;
    private static final int BINDING_INSTANCE = 2;

    private static int maxVertexAttribs;

    /**
     * {@code GL_MAX_VERTEX_ATTRIBS} (16 on every GL 3.3 device, sometimes more). The additional-GPU-data
     * attribute tail is laid out one attribute per enabled channel from the kind's base location, so a
     * config with many channels can run past it — see {@code AdditionalGPUDataSetting.planAttribs}, which
     * drops the overflow rather than issuing invalid GL calls. Render thread only.
     */
    public static int maxVertexAttribs() {
        if (maxVertexAttribs == 0) {
            maxVertexAttribs = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
        }
        return maxVertexAttribs;
    }

    @Nullable
    private static Boolean separateBindings;

    /**
     * Whether the engine drives VAOs through ARB_vertex_attrib_binding — mirrors the condition in
     * {@code VertexArrayCache.create} so we always speak the same model it does (it falls back to
     * {@code Emulated} on drivers without the extension). {@code GlDevice} is package-private, hence
     * the reflective read of its toggle; if that ever disappears we fall back to the capability alone,
     * which is what actually decides support.
     */
    private static boolean useSeparateBindings() {
        var cached = separateBindings;
        if (cached != null) {
            return cached;
        }
        var supported = org.lwjgl.opengl.GL.getCapabilities().GL_ARB_vertex_attrib_binding;
        var enabled = true;
        try {
            var field = Class.forName("com.mojang.blaze3d.opengl.GlDevice")
                    .getDeclaredField("USE_GL_ARB_vertex_attrib_binding");
            field.setAccessible(true);
            enabled = field.getBoolean(null);
        } catch (Throwable ignored) {
        }
        separateBindings = supported && enabled;
        return separateBindings;
    }

    /**
     * Attribute locations we enabled per VAO. A VAO is shared by every emitter drawing the same variant,
     * and the layouts are NOT the same length: the additional-GPU-data attribute tail is per emitter. An
     * enabled attribute array is VAO state that survives the draw, so an emitter with a short (or no) tail
     * would inherit the previous emitter's tail — enabled, pointing into binding {@link #BINDING_INSTANCE}
     * with the OTHER layout's offsets, i.e. fetching past its shorter stride. So each apply disables the
     * locations it no longer uses. Only ever disables locations Photon itself enabled.
     */
    private static final java.util.Map<Integer, Integer> ENABLED_BY_VAO = new java.util.HashMap<>();

    /** Called by {@code VertexArrayCacheMixin} after the engine bound our dedicated VAO. */
    public static void apply() {
        var layout = activeLayout;
        if (layout == null) {
            return;
        }
        if (useSeparateBindings()) {
            applySeparate(layout);
        } else {
            applyLegacy(layout);
        }
        syncEnabled(layout);
    }

    /** Disable whatever this layout doesn't use but a previous one on this VAO left enabled. */
    private static void syncEnabled(Layout layout) {
        var mask = 0;
        if (layout.baseAttribs() != null) {
            for (var attrib : layout.baseAttribs()) {
                mask |= 1 << attrib.location();
            }
        }
        for (var attrib : layout.attribs()) {
            mask |= 1 << attrib.location();
        }
        var vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        var stale = ENABLED_BY_VAO.getOrDefault(vao, 0) & ~mask;
        while (stale != 0) {
            var location = Integer.numberOfTrailingZeros(stale);
            GL20.glDisableVertexAttribArray(location);
            stale &= ~(1 << location);
        }
        ENABLED_BY_VAO.put(vao, mask);
    }

    /** ARB_vertex_attrib_binding: format/binding per attribute + one buffer bind and divisor per stream. */
    private static void applySeparate(Layout layout) {
        if (layout.baseAttribs() != null) {
            for (var attrib : layout.baseAttribs()) {
                format(attrib);
                ARBVertexAttribBinding.glVertexAttribBinding(attrib.location(), BINDING_BASE);
                GL20.glEnableVertexAttribArray(attrib.location());
            }
            ARBVertexAttribBinding.glBindVertexBuffer(BINDING_BASE, baseVbo, 0L,
                    layout.baseStride() * Float.BYTES);
            ARBVertexAttribBinding.glVertexBindingDivisor(BINDING_BASE, 0);
        }
        for (var attrib : layout.attribs()) {
            format(attrib);
            ARBVertexAttribBinding.glVertexAttribBinding(attrib.location(), BINDING_INSTANCE);
            GL20.glEnableVertexAttribArray(attrib.location());
        }
        ARBVertexAttribBinding.glBindVertexBuffer(BINDING_INSTANCE, instanceVbo, 0L,
                layout.strideFloats() * Float.BYTES);
        ARBVertexAttribBinding.glVertexBindingDivisor(BINDING_INSTANCE, 1);
    }

    /** The attribute's component layout; the offset is RELATIVE to its binding's buffer offset. */
    private static void format(Attrib attrib) {
        int relativeOffset = attrib.offsetFloats() * Float.BYTES;
        if (attrib.integer()) {
            ARBVertexAttribBinding.glVertexAttribIFormat(attrib.location(), attrib.size(),
                    GL11.GL_UNSIGNED_INT, relativeOffset);
        } else {
            ARBVertexAttribBinding.glVertexAttribFormat(attrib.location(), attrib.size(),
                    GL11.GL_FLOAT, false, relativeOffset);
        }
    }

    /** The pre-4.3 path, used when the engine falls back to {@code VertexArrayCache$Emulated}. */
    private static void applyLegacy(Layout layout) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            if (layout.baseAttribs() != null) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, baseVbo);
                for (var attrib : layout.baseAttribs()) {
                    pointer(attrib, layout.baseStride());
                    GL33.glVertexAttribDivisor(attrib.location(), 0);
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
            for (var attrib : layout.attribs()) {
                pointer(attrib, layout.strideFloats());
                GL33.glVertexAttribDivisor(attrib.location(), 1);
            }
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        }
    }

    private static void pointer(Attrib attrib, int strideFloats) {
        var stride = strideFloats * Float.BYTES;
        var offset = (long) attrib.offsetFloats() * Float.BYTES;
        if (attrib.integer()) {
            GL30.glVertexAttribIPointer(attrib.location(), attrib.size(), GL11.GL_UNSIGNED_INT, stride, offset);
        } else {
            GL20.glVertexAttribPointer(attrib.location(), attrib.size(), GL11.GL_FLOAT, false, stride, offset);
        }
        GL20.glEnableVertexAttribArray(attrib.location());
    }
}
