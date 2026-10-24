package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.compat.iris.IrisCompositeMode;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distortions that add up: each adds its push into a frame-sized RGBA16F sum, and the frame is bent once by the total,
 * so hazes in front of each other bend together. rg is the push in screen uv, b how far the tints darken, a the nearest
 * one's {@code 1 / (1 + eye)} kept by {@code GL_MAX} (a 26.1 clear is 8-bit, so 1.21's MIN over a far clear is out).
 * {@link #INLINE} is bent at the end of each in-place drain, {@link #LAYER} once the deferred layer is merged. Under a
 * shader pack only for {@link IrisCompositeMode#AFTER_PACK}: every other mode merges the FX into the pack's own targets
 * mid-frame, and the frame is the pack's.
 */
public final class StackedDistortion {
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
    /** Frames a size's buffers may go unused before they are freed. */
    private static final int IDLE_FRAMES = 120;
    /** The stacked variants of the materials' pipelines — the drain draws exactly these into the offsets. */
    private static final Set<RenderPipeline> STACKED = ConcurrentHashMap.newKeySet();

    public static final Accumulator INLINE = new Accumulator("Photon stacked distortion");
    public static final Accumulator LAYER = new Accumulator("Photon stacked distortion (layer)");

    @Nullable
    private static RenderPipeline bend;
    private static int frame;

    private StackedDistortion() {
    }

    /**
     * Whether a distortion drawing now can stack: with float targets, in a frame Photon owns — an editor scene is
     * one under a shader pack too — or one the pack finishes before Photon's layer is merged onto it
     * ({@link IrisCompositeMode#AFTER_PACK}), which is when {@link #LAYER} bends the finished frame.
     */
    public static boolean available() {
        if (RenderSystem.tryGetDevice() == null || !PhotonFloatTextures.isSupported()) return false;
        if (!IrisCompat.isUsingShaderPack() || PhotonParticleManager.isEditorSceneRendering()) return true;
        return IrisCompat.compositeMode() == IrisCompositeMode.AFTER_PACK;
    }

    /** Compile the pass that bends the frame ahead of the first stacked draw ({@code FXHelper.preload}). */
    public static void preload() {
        RenderSystem.getDevice().precompilePipeline(bendPipeline());
    }

    public static RenderPipeline mark(RenderPipeline pipeline) {
        STACKED.add(pipeline);
        return pipeline;
    }

    /** See {@link PhotonPipelines#release}. */
    static void unmark(RenderPipeline pipeline) {
        STACKED.remove(pipeline);
    }

    public static boolean isStacked(RenderPipeline pipeline) {
        return STACKED.contains(pipeline);
    }

    /**
     * Draw a stacked job: colour adds up (the pipeline's ONE, ONE), alpha keeps the nearest. A 26.1 pipeline has one
     * equation for both, so the split is a raw-GL escape around the draw.
     */
    static void withStackBlend(Runnable draw) {
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_MAX);
        try {
            draw.run();
        } finally {
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        }
    }

    /** Frame boundary: offsets a frame never bent must not land on the next one; long-unused buffers are freed. */
    public static void endFrame() {
        frame++;
        INLINE.endFrame();
        LAYER.endFrame();
    }

    private static RenderPipeline bendPipeline() {
        if (bend == null) {
            bend = PhotonFullscreenPass.builder(Photon.id("core/kila_stack"))
                    .withLocation(Photon.id("pipeline/kila_stack"))
                    .withSampler("DiffuseSampler")
                    .withSampler("OffsetSampler")
                    .withSampler("DepthSampler")
                    .withUniform("PhotonEngine", UniformType.UNIFORM_BUFFER)
                    // the frame's alpha is not ours: in the editor it is the coverage the PIP composite reads
                    .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_COLOR))
                    .build();
        }
        return bend;
    }

    /**
     * One sum of offsets and the copies its bend reads, kept per frame size: the world and an editor scene draw into
     * targets of different sizes in the same frame. Render thread only.
     */
    public static final class Accumulator {
        private final String label;
        private final Map<Long, Buffers> bySize = new HashMap<>();

        private Accumulator(String label) {
            this.label = label;
        }

        private static long size(int width, int height) {
            return ((long) width << 32) | (height & 0xFFFFFFFFL);
        }

        /**
         * What a stacked draw into a {@code width x height} frame adds into — cleared before the first one since the
         * last bend. Null when the float target could not be made (the draw is then dropped). Before the pass opens.
         */
        @Nullable
        public GpuTextureView target(int width, int height) {
            var buffers = bySize.get(size(width, height));
            if (buffers == null) {
                var offsets = PhotonFloatTextures.createRgba16f(label, USAGE, width, height);
                if (offsets == null) return null;
                buffers = new Buffers(offsets, RenderSystem.getDevice().createTextureView(offsets));
                bySize.put(size(width, height), buffers);
            }
            buffers.lastUsed = frame;
            if (!buffers.pending) {
                //noinspection EmptyTryBlock
                try (var ignored = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> label + " clear", buffers.offsetsView, OptionalInt.of(0), null, OptionalDouble.empty())) {
                }
                buffers.pending = true;
            }
            return buffers.offsetsView;
        }

        /** Bend {@code frame} by what was summed for its size and start over; {@code depth} the frame's own. */
        public void bend(@Nullable GpuTextureView frame, @Nullable GpuTextureView depth) {
            if (frame == null || depth == null) return;
            int width = frame.texture().getWidth(0);
            int height = frame.texture().getHeight(0);
            var buffers = bySize.get(size(width, height));
            if (buffers == null || !buffers.pending) return;
            buffers.pending = false;
            var engine = PhotonEngineUniforms.currentSlice();
            if (engine == null || !buffers.ensureCopies(label, width, height, depth.texture().getFormat())) return;
            // blits, not copyTextureToTexture: the editor's targets carry no COPY_SRC (see PhotonDrawTarget)
            PhotonFramebufferBlit.color(frame.texture(), buffers.frameCopy, width, height);
            PhotonFramebufferBlit.depth(depth.texture(), buffers.depthCopy, width, height);
            var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            PhotonFullscreenPass.draw("Photon stacked distortion bend", bendPipeline(), frame, pass -> {
                pass.bindTexture("DiffuseSampler", buffers.frameCopyView, linear);
                pass.bindTexture("OffsetSampler", buffers.offsetsView, nearest);
                pass.bindTexture("DepthSampler", buffers.depthCopyView, nearest);
                pass.setUniform("PhotonEngine", engine);
            });
        }

        private void endFrame() {
            bySize.values().removeIf(buffers -> {
                buffers.pending = false;
                if (frame - buffers.lastUsed < IDLE_FRAMES) return false;
                buffers.close();
                return true;
            });
        }
    }

    /** One size's offsets, and the frame and depth copies a bend samples. */
    private static final class Buffers {
        final GpuTexture offsets;
        final GpuTextureView offsetsView;
        @Nullable
        GpuTexture frameCopy;
        @Nullable
        GpuTextureView frameCopyView;
        @Nullable
        GpuTexture depthCopy;
        @Nullable
        GpuTextureView depthCopyView;
        /** offsets were added since the last bend */
        boolean pending;
        int lastUsed;

        Buffers(GpuTexture offsets, GpuTextureView offsetsView) {
            this.offsets = offsets;
            this.offsetsView = offsetsView;
        }

        /** The copies a bend reads; false when the float frame copy could not be made. */
        boolean ensureCopies(String label, int width, int height, TextureFormat depthFormat) {
            var device = RenderSystem.getDevice();
            if (frameCopy == null) {
                // float whatever the frame declares: the in-place draw target is RGBA16F behind an RGBA8 declaration,
                // and a copy at the declared format would clamp the whole frame to 8 bits
                frameCopy = PhotonFloatTextures.createRgba16f(label + " frame", USAGE, width, height);
                if (frameCopy == null) return false;
                frameCopyView = device.createTextureView(frameCopy);
            }
            // a depth blit needs matching formats
            if (depthCopy == null || depthCopy.getFormat() != depthFormat) {
                if (depthCopyView != null) depthCopyView.close();
                if (depthCopy != null) depthCopy.close();
                depthCopy = device.createTexture(() -> label + " depth", USAGE, depthFormat, width, height, 1, 1);
                depthCopyView = device.createTextureView(depthCopy);
            }
            return true;
        }

        void close() {
            for (var view : new GpuTextureView[]{offsetsView, frameCopyView, depthCopyView}) {
                if (view != null) view.close();
            }
            for (var texture : new GpuTexture[]{offsets, frameCopy, depthCopy}) {
                if (texture != null) texture.close();
            }
        }
    }
}
