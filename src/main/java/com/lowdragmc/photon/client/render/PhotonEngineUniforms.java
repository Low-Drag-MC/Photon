package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code PhotonEngine} std140 block (see {@code shaders/include/engine.glsl}) — the 1.21
 * {@code U_*} dynamic uniforms. One shared GPU buffer, re-uploaded whenever a render context
 * changes it: once per world frame (FrameGraphSetupEvent, camera state) and per editor-scene
 * render (scene camera state). Uploads MUST happen outside an open render pass; both hooks
 * run in extraction/setup phases, which qualify.
 */
public final class PhotonEngineUniforms {

    /** std140: mat4 + mat4 + vec4 + vec4. */
    private static final int STD140_SIZE = 160;

    @Nullable
    private static GpuBuffer buffer;
    /** RenderTypes whose pipeline declares the PhotonEngine block (custom user shaders). */
    private static final Set<RenderType> ENGINE_RENDER_TYPES = ConcurrentHashMap.newKeySet();

    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE_VIEW = new Matrix4f();

    /** The current view's depth range, recovered from its projection — see {@link #zNear()}. */
    private static float zNear = 0.05f;
    private static float zFar = 1000f;

    /**
     * The near/far planes of the view Photon is drawing for.
     * <p>
     * Screen-space effects that reason about DISTANCE (an outline's silhouette test, depth of field)
     * cannot use the raw depth buffer directly: it stores a hyperbolic encoding, so the same physical
     * step produces wildly different gradients depending on how far away it is. Linearising needs the
     * two planes, and this is where they are known — {@link #update} is handed the very projection the
     * view renders with, so an editor scene reports ITS planes rather than the world's.
     */
    public static float zNear() {
        return zNear;
    }

    public static float zFar() {
        return zFar;
    }

    /**
     * Recover near/far from a projection matrix. JOML already inverts the perspective case
     * ({@code perspectiveNear/Far}); orthographic is the branch it does not cover, and an editor scene
     * may use either, so it is derived here ({@code m22 = -2/(f-n)}, {@code m32 = -(f+n)/(f-n)}).
     * A projection we cannot make sense of leaves the previous range in place rather than poisoning it.
     */
    private static void captureDepthRange(Matrix4fc projection) {
        float near;
        float far;
        if (projection.m23() < -0.5f) { // perspective (m23 == -1)
            near = projection.perspectiveNear();
            far = projection.perspectiveFar();
        } else if (projection.m22() != 0f) {
            near = (projection.m32() + 1f) / projection.m22();
            far = (projection.m32() - 1f) / projection.m22();
        } else {
            return;
        }
        if (Float.isFinite(near) && Float.isFinite(far) && far > near) {
            zNear = near;
            zFar = far;
        }
    }

    private PhotonEngineUniforms() {
    }

    /** Mark a custom-shader RenderType as needing the engine block bound at draw. */
    public static void register(RenderType renderType) {
        ENGINE_RENDER_TYPES.add(renderType);
    }

    /** Drop a dead RenderType's registration (shader invalidation — prevents registry leaks). */
    public static void unregister(RenderType renderType) {
        ENGINE_RENDER_TYPES.remove(renderType);
    }

    /** The current engine block slice (instanced draws bind without a RenderType), or null when
     *  nothing was uploaded yet this session. */
    @Nullable
    public static GpuBufferSlice currentSlice() {
        return buffer == null ? null : buffer.slice();
    }

    /** The slice to bind for this RenderType's draw, or null when it doesn't use the engine block
     *  (or nothing was uploaded yet this session). */
    @Nullable
    public static GpuBufferSlice sliceFor(RenderType renderType) {
        if (buffer == null || !ENGINE_RENDER_TYPES.contains(renderType)) {
            return null;
        }
        return buffer.slice();
    }

    /** Byte offset of the {@code U_ViewPort} vec4 in the std140 block (mat4 + mat4 + vec4 before it). */
    private static final int VIEWPORT_OFFSET = 144;

    /**
     * Rewrite ONLY {@code U_ViewPort}, leaving the matrices alone.
     * <p>
     * The viewport is the size of the target being drawn into, and every scene-capture sample divides
     * {@code gl_FragCoord} by it. A view can know that without knowing its camera matrices — which is
     * exactly the editor scene's situation: LDLib2's {@code buildCameraRenderState()} fills only
     * pos/blockPos, so there is no projection/view to publish, and folding the viewport into
     * {@link #update} meant it silently kept the world frame's window size for the whole scene render.
     */
    public static void updateViewport(float width, float height) {
        RenderSystem.assertOnRenderThread();
        if (buffer == null) {
            return; // nothing uploaded yet — the first full update will carry this viewport anyway
        }
        var bytes = MemoryUtil.memAlloc(16);
        try {
            bytes.putFloat(0).putFloat(0).putFloat(width).putFloat(height);
            bytes.rewind();
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToBuffer(buffer.slice(VIEWPORT_OFFSET, 16), bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    /**
     * Upload the per-frame values. {@code projection}/{@code viewRotation} are the CPU-side camera
     * matrices ({@code CameraRenderState.projectionMatrix}/{@code viewRotationMatrix}); the view
     * matrix is camera-relative, matching what the 1.21 shaders reconstructed world positions with.
     */
    public static void update(Matrix4fc projection, Matrix4fc viewRotation, Vector3fc cameraPos,
                              float viewportWidth, float viewportHeight) {
        RenderSystem.assertOnRenderThread();
        if (buffer == null) {
            buffer = RenderSystem.getDevice().createBuffer(
                    () -> "PhotonEngine UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    STD140_SIZE);
        }
        projection.invert(INVERSE_PROJECTION);
        viewRotation.invert(INVERSE_VIEW);
        captureDepthRange(projection);
        var bytes = MemoryUtil.memAlloc(STD140_SIZE);
        try {
            Std140Builder.intoBuffer(bytes)
                    .putMat4f(INVERSE_PROJECTION)
                    .putMat4f(INVERSE_VIEW)
                    .putVec4(cameraPos.x(), cameraPos.y(), cameraPos.z(), 1)
                    .putVec4(0, 0, viewportWidth, viewportHeight);
            bytes.rewind();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }
}
