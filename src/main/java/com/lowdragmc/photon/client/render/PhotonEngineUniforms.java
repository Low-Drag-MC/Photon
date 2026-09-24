package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;

/**
 * The {@code PhotonEngine} block ({@code include/engine.glsl}): the 1.21 {@code U_*} uniforms plus depth conventions.
 * One shared buffer re-uploaded per view; {@code writeToBuffer} is ordered with the recorded commands on both
 * backends, so each view's draws see its own values. Uploads must happen outside a render pass.
 */
public final class PhotonEngineUniforms {

    public static final String UBO_NAME = "PhotonEngine";

    /** mat4 + mat4 + vec4 + vec4 + vec4. */
    private static final int STD140_SIZE = 176;
    private static final int VIEWPORT_OFFSET = 144;

    @Nullable
    private static GpuBuffer buffer;
    @Nullable
    private static GpuBuffer legacyBuffer;
    private static final Matrix4f FORWARD_PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE_FORWARD_PROJECTION = new Matrix4f();

    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE_VIEW = new Matrix4f();
    private static final Vector4f SCRATCH = new Vector4f();

    private static float zNear = 0.05f;
    private static float zFar = 1000f;

    private PhotonEngineUniforms() {
    }

    /** Near plane of the current view, for effects that linearise depth. */
    public static float zNear() {
        return zNear;
    }

    public static float zFar() {
        return zFar;
    }

    /** {@code ndcZ = depth * scale + bias}: (1, 0) for a [0,1] clip range, else (2, -1). */
    public static float depthZScale() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1f : 2f;
    }

    public static float depthZBias() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 0f : -1f;
    }

    /**
     * Unprojects both ends of the depth range, which holds for reverse-Z, either NDC range and orthographic views
     * (JOML's {@code perspectiveNear/Far} assume forward-Z GL).
     */
    private static void captureDepthRange(Matrix4fc inverseProjection) {
        var a = eyeDistance(inverseProjection, 0f);
        var b = eyeDistance(inverseProjection, 1f);
        var near = Math.min(a, b);
        var far = Math.max(a, b);
        if (Float.isFinite(near) && Float.isFinite(far) && far > near && near >= 0f) {
            zNear = near;
            zFar = far;
        }
    }

    private static float eyeDistance(Matrix4fc inverseProjection, float depth) {
        var ndcZ = depth * depthZScale() + depthZBias();
        inverseProjection.transform(SCRATCH.set(0f, 0f, ndcZ, 1f));
        return -SCRATCH.z / SCRATCH.w;
    }

    @Nullable
    public static GpuBufferSlice currentSlice() {
        return buffer == null ? null : buffer.slice();
    }

    /** Seeds a neutral camera for views drawn before the first world frame; Vulkan fails on an unbound block. */
    public static GpuBufferSlice ensure(float viewportWidth, float viewportHeight) {
        if (buffer == null) {
            update(NEUTRAL, NEUTRAL, ORIGIN, viewportWidth, viewportHeight);
        }
        return buffer.slice();
    }

    /**
     * The block for legacy (forward-Z) custom shaders: {@code U_InverseProjectionMatrix} inverts the forward GL
     * projection, matching the {@code 1 - depth} of {@code PhotonSceneCapture.legacyDepth}.
     */
    public static GpuBufferSlice legacySlice() {
        return legacyBuffer != null ? legacyBuffer.slice() : ensure(1, 1);
    }

    private static final Matrix4f NEUTRAL = new Matrix4f();
    private static final Vector3f ORIGIN = new Vector3f();

    /** Rewrites only {@code U_ViewPort}, for views that know their size but not their camera. */
    public static void updateViewport(float width, float height) {
        RenderSystem.assertOnRenderThread();
        if (buffer == null) {
            ensure(width, height);
            return;
        }
        var bytes = MemoryUtil.memAlloc(16);
        try {
            bytes.putFloat(0).putFloat(0).putFloat(width).putFloat(height);
            bytes.rewind();
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToBuffer(buffer.slice(VIEWPORT_OFFSET, 16), bytes);
            if (legacyBuffer != null) {
                bytes.rewind();
                encoder.writeToBuffer(legacyBuffer.slice(VIEWPORT_OFFSET, 16), bytes);
            }
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    /** {@code viewRotation} is camera-relative, as the 1.21 shaders expect. */
    public static void update(Matrix4fc projection, Matrix4fc viewRotation, Vector3fc cameraPos,
                              float viewportWidth, float viewportHeight) {
        RenderSystem.assertOnRenderThread();
        if (buffer == null) {
            buffer = RenderSystem.getDevice().createBuffer(
                    () -> "PhotonEngine UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    STD140_SIZE);
            legacyBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "PhotonEngine UBO (legacy depth)",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    STD140_SIZE);
        }
        projection.invert(INVERSE_PROJECTION);
        viewRotation.invert(INVERSE_VIEW);
        captureDepthRange(INVERSE_PROJECTION);
        DepthConventions.forwardProjection(projection, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),
                FORWARD_PROJECTION).invert(INVERSE_FORWARD_PROJECTION);
        write(buffer, INVERSE_PROJECTION, cameraPos, viewportWidth, viewportHeight);
        write(legacyBuffer, INVERSE_FORWARD_PROJECTION, cameraPos, viewportWidth, viewportHeight);
    }

    private static void write(GpuBuffer target, Matrix4fc inverseProjection, Vector3fc cameraPos,
                              float viewportWidth, float viewportHeight) {
        var bytes = MemoryUtil.memAlloc(STD140_SIZE);
        try {
            Std140Builder.intoBuffer(bytes)
                    .putMat4f(inverseProjection)
                    .putMat4f(INVERSE_VIEW)
                    .putVec4(cameraPos.x(), cameraPos.y(), cameraPos.z(), 1)
                    .putVec4(0, 0, viewportWidth, viewportHeight)
                    .putVec4(depthZScale(), depthZBias(), zNear, zFar);
            bytes.rewind();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(), bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }
}
