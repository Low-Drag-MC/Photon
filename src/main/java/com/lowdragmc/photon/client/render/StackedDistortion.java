package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.PhotonParticleManager;
import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.lowdragmc.photon.client.compat.iris.IrisCompositeMode;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distortions that add up: each adds its push into a frame-sized RGBA16F sum, and the frame is bent once by the total,
 * so hazes in front of each other bend together. rg is the push in screen uv, b how far the tints darken, a the nearest
 * one's {@code 1 / (1 + eye)}, kept by a MAX on alpha ({@link #STACK_BLEND}). {@link #INLINE} is bent at the end of
 * each in-place drain, {@link #LAYER} once the deferred layer is merged. Under a shader pack only for
 * {@link IrisCompositeMode#AFTER_PACK}: every other mode merges the FX into the pack's own targets mid-frame, and the
 * frame is the pack's.
 */
public final class StackedDistortion {
    /** Colour adds up; alpha keeps the nearest, the larger {@code 1 / (1 + eye)}. */
    public static final BlendFunction STACK_BLEND = new BlendFunction(
            new BlendEquation(BlendFactor.ONE, BlendFactor.ONE, BlendOp.ADD),
            new BlendEquation(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX));
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_COPY_DST;
    /** Frames a size's buffers may go unused before they are freed. */
    private static final int IDLE_FRAMES = 120;
    /** The stacked variants of the materials' pipelines — the drain draws exactly these into the offsets. */
    private static final Set<RenderPipeline> STACKED = ConcurrentHashMap.newKeySet();
    /** The bend, per format of the frame it bends: the in-place draw target, or the output a layer merged onto. */
    private static final Map<GpuFormat, RenderPipeline> BENDS = new ConcurrentHashMap<>();

    public static final Accumulator INLINE = new Accumulator("Photon stacked distortion");
    public static final Accumulator LAYER = new Accumulator("Photon stacked distortion (layer)");

    private static int frame;

    private StackedDistortion() {
    }

    /**
     * Whether a distortion drawing now can stack: in a frame Photon owns — an editor scene is one under a shader pack
     * too — or one the pack finishes before Photon's layer is merged onto it ({@link IrisCompositeMode#AFTER_PACK}),
     * which is when {@link #LAYER} bends the finished frame.
     */
    public static boolean available() {
        if (RenderSystem.tryGetDevice() == null) return false;
        if (!IrisCompat.isUsingShaderPack() || PhotonParticleManager.isEditorSceneRendering()) return true;
        return IrisCompat.compositeMode() == IrisCompositeMode.AFTER_PACK;
    }

    /** Compile the bends of the formats a frame comes in ahead of the first stacked draw. */
    public static void preload() {
        var device = RenderSystem.getDevice();
        device.precompilePipeline(bendPipeline(PhotonPipelines.HDR_FORMAT));
        device.precompilePipeline(bendPipeline(
                Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture().getFormat()));
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

    /** Frame boundary: offsets a frame never bent must not land on the next one; long-unused buffers are freed. */
    public static void endFrame() {
        frame++;
        INLINE.endFrame();
        LAYER.endFrame();
    }

    private static RenderPipeline bendPipeline(GpuFormat format) {
        return BENDS.computeIfAbsent(format, f -> PhotonFullscreenPass.builder(Photon.id("core/kila_stack"))
                .withLocation(Photon.id("pipeline/kila_stack_" + f.name().toLowerCase(Locale.ROOT)))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withSampler("DiffuseSampler")
                        .withSampler("OffsetSampler")
                        .withSampler("DepthSampler")
                        .withUniform(PhotonEngineUniforms.UBO_NAME, UniformType.UNIFORM_BUFFER)
                        .build())
                // the frame's alpha is not ours: in the editor it is the coverage the PIP composite reads
                .withColorTargetState(new ColorTargetState(Optional.empty(), f, ColorTargetState.WRITE_COLOR))
                .build());
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
         * last bend. Before the pass opens.
         */
        public GpuTextureView target(int width, int height) {
            var buffers = bySize.get(size(width, height));
            if (buffers == null) {
                // a new size is most often a resize: what neither this frame nor the last used would pile up
                bySize.values().removeIf(stale -> {
                    if (frame - stale.lastUsed <= 1) return false;
                    stale.close();
                    return true;
                });
                buffers = new Buffers(label, width, height);
                bySize.put(size(width, height), buffers);
            }
            buffers.lastUsed = frame;
            if (!buffers.pending) {
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(buffers.offsets,
                        new Vector4f(0, 0, 0, 0));
                buffers.pending = true;
            }
            return buffers.offsetsView;
        }

        /**
         * Bend {@code frame} by what was summed for its size and start over; {@code depth} the frame's own, sampled
         * through a copy. Inside {@code scissor} when one is given.
         */
        public void bend(@Nullable GpuTextureView frame, @Nullable GpuTextureView depth,
                         @Nullable ScissorState scissor) {
            if (frame == null || depth == null) return;
            var buffers = bySize.get(size(frame.getWidth(0), frame.getHeight(0)));
            if (buffers == null || !buffers.pending) return;
            buffers.pending = false;
            var engine = PhotonEngineUniforms.currentSlice();
            var frameCopy = buffers.frameCopy.copyOf(frame);
            var depthCopy = buffers.depthCopy.copyOf(depth);
            if (engine == null || frameCopy == null || depthCopy == null) return;
            var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            PhotonFullscreenPass.draw("Photon stacked distortion bend", bendPipeline(frame.texture().getFormat()),
                    frame, scissor, pass -> {
                        pass.bindTexture("DiffuseSampler", frameCopy, linear);
                        pass.bindTexture("OffsetSampler", buffers.offsetsView, nearest);
                        pass.bindTexture("DepthSampler", depthCopy, nearest);
                        pass.setUniform(PhotonEngineUniforms.UBO_NAME, engine);
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
        final PhotonTextureCopy frameCopy;
        final PhotonTextureCopy depthCopy;
        /** offsets were added since the last bend */
        boolean pending;
        int lastUsed;

        Buffers(String label, int width, int height) {
            var device = RenderSystem.getDevice();
            offsets = device.createTexture(() -> label, USAGE, PhotonPipelines.HDR_FORMAT, width, height, 1, 1);
            offsetsView = device.createTextureView(offsets);
            frameCopy = new PhotonTextureCopy(label + " frame");
            depthCopy = new PhotonTextureCopy(label + " depth");
        }

        void close() {
            offsetsView.close();
            offsets.close();
            frameCopy.close();
            depthCopy.close();
        }
    }
}
