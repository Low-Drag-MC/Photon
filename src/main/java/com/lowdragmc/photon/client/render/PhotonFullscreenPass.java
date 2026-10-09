package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The one fullscreen draw every screen-space stage of Photon is built from — bloom's mip chain, the
 * post-effect passes and the chain's mix/composite steps all reduce to "run this pipeline over the
 * whole of one target".
 * <p>
 * The quad is a shared {@code [0,1]²} POSITION buffer, which the
 * {@value #VERTEX_SHADER_PATH} vertex stage passes through as {@code texCoord} and expands to NDC.
 * Working in 0..1 rather than ±1 is what makes a pass's own uv resolution-independent: a half-res
 * pass samples its full-res input at the same coordinates. A compiled fullscreen graph takes POSITION as clip
 * space, so it draws the ±1 quad ({@link #drawClipSpace}).
 * <p>
 * Callers only bind. Index buffer acquisition happens BEFORE the pass opens, because
 * {@code getSequentialBuffer} may grow (and therefore upload) its buffer, which is illegal once a
 * pass is active — the same reason texture resolution and UBO uploads are staged first everywhere
 * else in the drain. Render thread only.
 */
public final class PhotonFullscreenPass {

    /** The shared vertex stage; pass pipelines pair it with their own fragment shader. */
    public static final String VERTEX_SHADER_PATH = "core/fullscreen";

    private static GpuBuffer quadBuffer;
    private static GpuBuffer clipSpaceQuadBuffer;

    private PhotonFullscreenPass() {
    }

    /**
     * The geometry half of a fullscreen pipeline, with no shader stages — for a caller that supplies its
     * own vertex stage (a compiled shader graph generates both). Everything else here is what "fullscreen
     * quad" means, stated once.
     * <p>
     * No DepthStencilState at all: {@code wantsDepthTexture() == (state != null)}, and these passes render
     * into color-only targets — a state, even ALWAYS_PASS, makes every draw warn.
     */
    public static RenderPipeline.Builder builder() {
        return RenderPipeline.builder()
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
                .withCull(false);
    }

    /** A pipeline builder over the fullscreen quad — samplers and state are the caller's. */
    public static RenderPipeline.Builder builder(Identifier fragmentShader) {
        return builder()
                .withVertexShader(Photon.id(VERTEX_SHADER_PATH))
                .withFragmentShader(fragmentShader);
    }

    /**
     * Draw {@code pipeline} over the whole of {@code target}. {@code bind} runs inside the open pass and
     * must bind every sampler and uniform the pipeline declares — 26.1 validates that at draw.
     * <p>
     * Note what is NOT done here: {@code RenderSystem.bindDefaultUniforms}. It would bind {@code Projection},
     * {@code Fog}, {@code Globals} and {@code Lighting}, none of which a fullscreen pass has a use for, and
     * declaring them just to satisfy the bind would warn on every draw. A pass that wants a per-frame value
     * takes it in its own block instead.
     */
    public static void draw(String label, RenderPipeline pipeline, GpuTextureView target,
                            Consumer<RenderPass> bind) {
        draw(label, pipeline, target, null, bind);
    }

    /** {@link #draw(String, RenderPipeline, GpuTextureView, Consumer)} with a depth attachment, for a pipeline
     *  whose state tests or writes depth (the pass is otherwise the same). */
    public static void draw(String label, RenderPipeline pipeline, GpuTextureView target,
                            @Nullable GpuTextureView depth, Consumer<RenderPass> bind) {
        draw(label, pipeline, target, depth, quadBuffer(), bind);
    }

    /** {@link #draw(String, RenderPipeline, GpuTextureView, Consumer)} over the ±1 quad (FullscreenPositionBlock). */
    public static void drawClipSpace(String label, RenderPipeline pipeline, GpuTextureView target,
                                     Consumer<RenderPass> bind) {
        draw(label, pipeline, target, null, clipSpaceQuadBuffer(), bind);
    }

    private static void draw(String label, RenderPipeline pipeline, GpuTextureView target,
                             @Nullable GpuTextureView depth, GpuBuffer quad, Consumer<RenderPass> bind) {
        // both buffers are materialized BEFORE the pass opens: the index buffer may grow (and upload),
        // and the quad's first use allocates it — neither is legal once a pass is active
        var autoIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indices = autoIndices.getBuffer(6);
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> label, PhotonDepthPairs.color(target, depth), OptionalInt.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            bind.accept(pass);
            pass.setVertexBuffer(0, quad);
            pass.setIndexBuffer(indices, autoIndices.type());
            pass.drawIndexed(0, 0, 6, 1);
        }
    }

    /** The shared {@code [0,1]²} quad, counter-clockwise. */
    private static GpuBuffer quadBuffer() {
        if (quadBuffer == null) quadBuffer = quad("Photon fullscreen quad", 0);
        return quadBuffer;
    }

    /** The shared ±1 quad, counter-clockwise. */
    private static GpuBuffer clipSpaceQuadBuffer() {
        if (clipSpaceQuadBuffer == null) clipSpaceQuadBuffer = quad("Photon fullscreen quad (clip space)", -1);
        return clipSpaceQuadBuffer;
    }

    private static GpuBuffer quad(String label, float min) {
        var bytes = MemoryUtil.memAlloc(4 * 3 * Float.BYTES);
        try {
            bytes.putFloat(min).putFloat(min).putFloat(0)
                    .putFloat(1).putFloat(min).putFloat(0)
                    .putFloat(1).putFloat(1).putFloat(0)
                    .putFloat(min).putFloat(1).putFloat(0)
                    .flip();
            return RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }
}
