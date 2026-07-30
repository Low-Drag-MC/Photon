package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 1.21 bloom mip-chain on 26.1 render passes: bright pass (luma threshold + soft knee) →
 * 13-tap down-sample chain → additive 3x3 tent up-sample chain → composite onto the target as
 * {@code main + I*(bloom - sharpHighlight)} split into a REVERSE_SUBTRACT draw and an additive draw
 * (the raw {@code glBlendEquation} escape approved in M3 D4 — blaze3d has no blend equations).
 * <p>
 * The chain is RGBA16F throughout ({@link PhotonFloatTextures}), so it works in real HDR — the
 * threshold compares against actual luma rather than an encoded approximation, and no dynamic-range
 * juggling is needed anywhere.
 * <p>
 * One instance per target size (world + editor scenes differ), evicted when unused. Render thread only.
 */
public final class PhotonBloom implements AutoCloseable {

    private static final int TEXTURE_USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;

    // ---- per-size instances ----------------------------------------------------------------------

    private static final Map<Long, PhotonBloom> INSTANCES = new ConcurrentHashMap<>();
    /** Sizes whose chain could not be allocated — keeps the warning to once per size. */
    private static final java.util.Set<Long> FAILED = ConcurrentHashMap.newKeySet();
    private static long frameCounter;

    /** The chain for a target of this size, or null when it could not be allocated (skip bloom). */
    @Nullable
    public static PhotonBloom acquire(int width, int height) {
        var key = ((long) width << 32) | (height & 0xFFFFFFFFL);
        var bloom = INSTANCES.get(key);
        if (bloom == null) {
            if (FAILED.contains(key)) {
                return null;
            }
            bloom = create(width, height);
            if (bloom == null) {
                FAILED.add(key);
                Photon.LOGGER.error("Photon could not allocate its {}x{} RGBA16F bloom chain — bloom "
                        + "is off at this size", width, height);
                return null;
            }
            INSTANCES.put(key, bloom);
        }
        bloom.lastUsedFrame = frameCounter;
        return bloom;
    }

    /** Allocate the whole chain up front, so a partly-built instance can never escape. */
    @Nullable
    private static PhotonBloom create(int width, int height) {
        var allocated = new java.util.ArrayList<GpuTexture>();
        var source = chainTexture("Photon bloom source", Math.max(1, width), Math.max(1, height), allocated);
        var levels = Math.clamp(PhotonConfig.INSTANCE.bloomMipLevel.get(), 1, 10);
        var mips = new java.util.ArrayList<GpuTexture>();
        var w = Math.max(1, width / 2);
        var h = Math.max(1, height / 2);
        for (int i = 0; i < levels && w >= 8 && h >= 8; i++) {
            var mip = chainTexture("Photon bloom mip", w, h, allocated);
            if (mip != null) {
                mips.add(mip);
            }
            w /= 2;
            h /= 2;
        }
        if (source == null || mips.size() != allocated.size() - 1) {
            allocated.forEach(GpuTexture::close);
            return null;
        }
        return new PhotonBloom(source, mips.toArray(new GpuTexture[0]));
    }

    @Nullable
    private static GpuTexture chainTexture(String label, int width, int height,
                                           java.util.List<GpuTexture> allocated) {
        var texture = PhotonFloatTextures.createRgba16f(label, TEXTURE_USAGE, width, height);
        if (texture != null) {
            allocated.add(texture);
        }
        return texture;
    }

    /** Frame boundary: drop target sets nothing rendered with for a while (closed editors, resizes). */
    public static void endFrame() {
        frameCounter++;
        INSTANCES.values().removeIf(bloom -> {
            if (frameCounter - bloom.lastUsedFrame > 120) {
                bloom.close();
                return true;
            }
            return false;
        });
    }

    // ---- pipelines (lazy; bright variants keyed by config threshold) -----------------------------

    private static final BlendFunction ADDITIVE = new BlendFunction(SourceFactor.ONE, DestFactor.ONE);
    /** Composite draws must leave the target's ALPHA untouched: the editor scene FBO's alpha is
     *  premultiplied COVERAGE (transparent background) — additive fullscreen passes writing alpha
     *  turned the whole backdrop opaque black. RGB-only write mask. */
    private static final ColorTargetState ADDITIVE_RGB =
            new ColorTargetState(java.util.Optional.of(ADDITIVE), ColorTargetState.WRITE_COLOR);

    private static final Map<Float, RenderPipeline> BRIGHT_VARIANTS = new HashMap<>();
    private static final Map<Float, RenderPipeline> BLIT_VARIANTS = new HashMap<>();
    @Nullable
    private static RenderPipeline downPipeline;
    @Nullable
    private static RenderPipeline upPipeline;
    @Nullable
    private static GpuBuffer quadBuffer;

    private static RenderPipeline.Builder fullscreenBuilder(String fragment) {
        // no DepthStencilState at all: wantsDepthTexture() == (state != null), and these passes
        // render into color-only targets — a state (even ALWAYS_PASS) makes every draw warn
        return RenderPipeline.builder()
                .withVertexShader(Photon.id("core/bloom_fullscreen"))
                .withFragmentShader(Photon.id("core/" + fragment))
                .withSampler("inputSampler")
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
                .withCull(false);
    }

    /** The converted 1.21 bright_pass: Threshold/Knee ride in the PhotonBloom UBO; outputScale 1 starts
     *  the chain (plain overwrite into mip 0), = intensity for the full-res sharp-highlight pass
     *  (REVERSE_SUBTRACT onto the target). */
    private static RenderPipeline brightPipeline(float outputScale) {
        return BRIGHT_VARIANTS.computeIfAbsent(outputScale, scale ->
                fullscreenBuilder("bright_pass")
                        .withLocation(Photon.id("pipeline/bloom_bright_" + BRIGHT_VARIANTS.size()))
                        .withUniform("PhotonBloom", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                        .withShaderDefine("OUTPUT_SCALE", scale)
                        .withColorTargetState(scale == 1f
                                ? ColorTargetState.DEFAULT : ADDITIVE_RGB)
                        .build());
    }

    private static RenderPipeline blitPipeline(float outputScale) {
        return BLIT_VARIANTS.computeIfAbsent(outputScale, scale ->
                fullscreenBuilder("bloom_blit")
                        .withLocation(Photon.id("pipeline/bloom_blit_" + BLIT_VARIANTS.size()))
                        .withShaderDefine("OUTPUT_SCALE", scale)
                        .withColorTargetState(ADDITIVE_RGB)
                        .build());
    }

    @Nullable
    private static RenderPipeline copyPipeline;

    /** Straight overwrite (no blend) — snapshots the HDR scene into {@link #source}. */
    private static RenderPipeline copyPipeline() {
        if (copyPipeline == null) {
            copyPipeline = fullscreenBuilder("bloom_blit")
                    .withLocation(Photon.id("pipeline/bloom_copy"))
                    .withShaderDefine("OUTPUT_SCALE", 1f)
                    .build();
        }
        return copyPipeline;
    }

    private static RenderPipeline downPipeline() {
        if (downPipeline == null) {
            downPipeline = fullscreenBuilder("down_sampling")
                    .withLocation(Photon.id("pipeline/bloom_down"))
                    .withUniform("PhotonBloom", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                    .build();
        }
        return downPipeline;
    }

    private static RenderPipeline upPipeline() {
        if (upPipeline == null) {
            upPipeline = fullscreenBuilder("up_sampling")
                    .withLocation(Photon.id("pipeline/bloom_up"))
                    .withUniform("PhotonBloom", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                    .withColorTargetState(new ColorTargetState(ADDITIVE))
                    .build();
        }
        return upPipeline;
    }

    // ---- PhotonBloom UBO slices (the 1.21 loose uniforms; per-file block contents) ---------------

    /** 1.21 never set Knee in code — the shader-JSON default 0.1 was the live value. */
    private static final float KNEE = 0.1f;
    /** 1.21 set filterRadius to the constant 0.005 for the whole up-sample chain. */
    private static final float FILTER_RADIUS = 0.005f;

    private static final Map<Float, com.mojang.blaze3d.buffers.GpuBufferSlice> BRIGHT_PARAMS = new HashMap<>();
    private static final Map<Long, com.mojang.blaze3d.buffers.GpuBufferSlice> RESOLUTION_PARAMS = new HashMap<>();
    @Nullable
    private static com.mojang.blaze3d.buffers.GpuBufferSlice radiusParams;

    private static com.mojang.blaze3d.buffers.GpuBufferSlice paramsSlice(String label, float... values) {
        var bytes = MemoryUtil.memCalloc(16);
        try {
            for (var value : values) {
                bytes.putFloat(value);
            }
            bytes.rewind();
            var buffer = RenderSystem.getDevice().createBuffer(() -> label,
                    GpuBuffer.USAGE_UNIFORM, bytes);
            return buffer.slice();
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    /** bright_pass block: {@code float Knee; float Threshold;} (declaration order). */
    private static com.mojang.blaze3d.buffers.GpuBufferSlice brightParams(float threshold) {
        return BRIGHT_PARAMS.computeIfAbsent(threshold, t -> paramsSlice("Photon bloom bright", KNEE, t));
    }

    /** down_sampling block: {@code vec2 inputResolution;} — the INPUT texture's size (1.21 set it per step). */
    private static com.mojang.blaze3d.buffers.GpuBufferSlice resolutionParams(int width, int height) {
        return RESOLUTION_PARAMS.computeIfAbsent(((long) width << 32) | (height & 0xFFFFFFFFL),
                key -> paramsSlice("Photon bloom resolution", width, height));
    }

    /** up_sampling block: {@code float filterRadius;}. */
    private static com.mojang.blaze3d.buffers.GpuBufferSlice radiusParams() {
        if (radiusParams == null) {
            radiusParams = paramsSlice("Photon bloom radius", FILTER_RADIUS);
        }
        return radiusParams;
    }

    private static GpuBuffer quadBuffer() {
        if (quadBuffer == null) {
            var bytes = MemoryUtil.memAlloc(4 * 3 * Float.BYTES);
            try {
                // [0,1]² quad, counter-clockwise
                bytes.putFloat(0).putFloat(0).putFloat(0)
                        .putFloat(1).putFloat(0).putFloat(0)
                        .putFloat(1).putFloat(1).putFloat(0)
                        .putFloat(0).putFloat(1).putFloat(0)
                        .flip();
                quadBuffer = RenderSystem.getDevice().createBuffer(() -> "Photon bloom quad",
                        GpuBuffer.USAGE_VERTEX, bytes);
            } finally {
                MemoryUtil.memFree(bytes);
            }
        }
        return quadBuffer;
    }

    // ---- per-size targets ------------------------------------------------------------------------

    private final GpuTexture source;
    private final GpuTextureView sourceView;
    private final GpuTexture[] mips;
    private final GpuTextureView[] mipViews;
    private long lastUsedFrame;

    private PhotonBloom(GpuTexture source, GpuTexture[] mips) {
        var device = RenderSystem.getDevice();
        this.source = source;
        this.sourceView = device.createTextureView(source);
        this.mips = mips;
        this.mipViews = new GpuTextureView[mips.length];
        for (int i = 0; i < mips.length; i++) {
            mipViews[i] = device.createTextureView(mips[i]);
        }
    }

    /**
     * Run the chain and composite onto {@code targetColor}: bright → down → up →
     * {@code target += I * (bloom - sharpHighlight)} (subtract first to avoid clamping the sum).
     * <p>
     * {@code targetColor} is the HDR draw target the frame's fx were just drawn into — it IS the input,
     * so nothing has to be re-rendered for bloom's sake. What glows is decided by the luma threshold
     * alone; the scene copied into that target is LDR by construction, so at the default threshold of 1
     * only genuine HDR fx cross it.
     */
    public void run(GpuTextureView targetColor) {
        if (mips.length == 0) {
            return;
        }
        var threshold = PhotonConfig.INSTANCE.bloomThreshold.get().floatValue();
        var intensity = PhotonConfig.INSTANCE.bloomIntensity.get().floatValue();

        // Snapshot the HDR scene. The chain could sample targetColor directly — it already holds exactly
        // what bloom needs, un-clamped — except the sharp-highlight subtract below both READS its input and
        // WRITES targetColor, and sampling a texture bound as the render attachment is undefined. One
        // full-screen copy buys that separation; it replaces re-drawing every emitter a second time.
        fullscreenPass(copyPipeline(), sourceView, targetColor, null);

        fullscreenPass(brightPipeline(1f), mipViews[0], sourceView, brightParams(threshold));
        for (int i = 1; i < mips.length; i++) {
            fullscreenPass(downPipeline(), mipViews[i], mipViews[i - 1],
                    resolutionParams(mips[i - 1].getWidth(0), mips[i - 1].getHeight(0)));
        }
        for (int i = mips.length - 2; i >= 0; i--) {
            fullscreenPass(upPipeline(), mipViews[i], mipViews[i + 1], radiusParams());
        }
        // subtract the sharp highlight (its blurred energy arrives via the bloom add below)
        GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
        try {
            fullscreenPass(brightPipeline(intensity), targetColor, sourceView, brightParams(threshold));
        } finally {
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        }
        fullscreenPass(blitPipeline(intensity), targetColor, mipViews[0], null);
    }

    private static void fullscreenPass(RenderPipeline pipeline, GpuTextureView target, GpuTextureView input,
                                       @Nullable com.mojang.blaze3d.buffers.GpuBufferSlice params) {
        var autoIndices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indices = autoIndices.getBuffer(6);
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Photon bloom", target, OptionalInt.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("inputSampler", input,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            if (params != null) {
                pass.setUniform("PhotonBloom", params);
            }
            pass.setVertexBuffer(0, quadBuffer());
            pass.setIndexBuffer(indices, autoIndices.type());
            pass.drawIndexed(0, 0, 6, 1);
        }
    }

    @Override
    public void close() {
        sourceView.close();
        source.close();
        for (var view : mipViews) {
            view.close();
        }
        for (var mip : mips) {
            mip.close();
        }
    }
}
