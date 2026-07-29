package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
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
 * 26.1 blaze3d has no float color formats, so the chain runs in ENCODED space: the fx bloom source
 * is drawn with {@code ColorModulator = 1/HDR_SCALE}, giving RGBA8 a [0, {@value #HDR_SCALE}]
 * dynamic range; the composite decodes. Swap to real RGBA16F when the engine grows float formats.
 * <p>
 * One instance per target size (world + editor scenes differ), evicted when unused. Render thread only.
 */
public final class PhotonBloom implements AutoCloseable {

    /** 1 with true RGBA16F targets (GL backend); 4 on the encoded-RGBA8 fallback. Resolved when the
     *  first target set is created — always before any chain pipeline is built. */
    public static float HDR_SCALE = 1f;
    private static final int TEXTURE_USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;

    // ---- per-size instances ----------------------------------------------------------------------

    private static final Map<Long, PhotonBloom> INSTANCES = new ConcurrentHashMap<>();
    private static long frameCounter;

    public static PhotonBloom acquire(int width, int height) {
        var bloom = INSTANCES.computeIfAbsent(((long) width << 32) | (height & 0xFFFFFFFFL),
                key -> new PhotonBloom(width, height));
        bloom.lastUsedFrame = frameCounter;
        return bloom;
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

    private static final DepthStencilState NO_DEPTH = new DepthStencilState(CompareOp.ALWAYS_PASS, false);
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

    /** The converted 1.21 bright_pass: Threshold/Knee ride in the PhotonBloom UBO; outputScale 1 =
     *  encoded chain start (overwrite), = intensity for the full-res sharp-highlight pass (decoded,
     *  REVERSE_SUBTRACT onto main). */
    private static RenderPipeline brightPipeline(float outputScale) {
        return BRIGHT_VARIANTS.computeIfAbsent(outputScale, scale ->
                fullscreenBuilder("bright_pass")
                        .withLocation(Photon.id("pipeline/bloom_bright_" + BRIGHT_VARIANTS.size()))
                        .withUniform("PhotonBloom", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                        .withShaderDefine("PHOTON_HDR_SCALE", HDR_SCALE)
                        .withShaderDefine("OUTPUT_SCALE", (float) scale)
                        .withColorTargetState(scale == 1f
                                ? ColorTargetState.DEFAULT : ADDITIVE_RGB)
                        .build());
    }

    private static RenderPipeline blitPipeline(float outputScale) {
        return BLIT_VARIANTS.computeIfAbsent(outputScale, scale ->
                fullscreenBuilder("bloom_blit")
                        .withLocation(Photon.id("pipeline/bloom_blit_" + BLIT_VARIANTS.size()))
                        .withShaderDefine("OUTPUT_SCALE", (float) scale)
                        .withColorTargetState(ADDITIVE_RGB)
                        .build());
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

    private final int width;
    private final int height;
    private final GpuTexture source;
    private final GpuTextureView sourceView;
    private final GpuTexture[] mips;
    private final GpuTextureView[] mipViews;
    private long lastUsedFrame;

    private PhotonBloom(int width, int height) {
        var device = RenderSystem.getDevice();
        this.width = width;
        this.height = height;
        this.source = createChainTexture("Photon bloom source", Math.max(1, width), Math.max(1, height));
        this.sourceView = device.createTextureView(source);
        var levels = Math.clamp(PhotonConfig.INSTANCE.bloomMipLevel.get(), 1, 10);
        var mipList = new java.util.ArrayList<GpuTexture>();
        var w = Math.max(1, width / 2);
        var h = Math.max(1, height / 2);
        for (int i = 0; i < levels && w >= 8 && h >= 8; i++) {
            mipList.add(createChainTexture("Photon bloom mip", w, h));
            w /= 2;
            h /= 2;
        }
        this.mips = mipList.toArray(new GpuTexture[0]);
        this.mipViews = new GpuTextureView[mips.length];
        for (int i = 0; i < mips.length; i++) {
            mipViews[i] = device.createTextureView(mips[i]);
        }
    }

    /** True RGBA16F when the backend allows (HDR_SCALE 1); encoded RGBA8 fallback (HDR_SCALE 4). */
    private static GpuTexture createChainTexture(String label, int width, int height) {
        var floatTexture = PhotonFloatTextures.createRgba16f(label, TEXTURE_USAGE, width, height);
        if (floatTexture != null) {
            HDR_SCALE = 1f;
            return floatTexture;
        }
        HDR_SCALE = 4f;
        return RenderSystem.getDevice().createTexture(() -> label, TEXTURE_USAGE,
                TextureFormat.RGBA8, width, height, 1, 1);
    }

    public GpuTextureView sourceView() {
        return sourceView;
    }

    public void clearSource() {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(source, 0);
    }

    /**
     * Run the chain and composite onto {@code targetColor}: bright → down → up →
     * {@code target += I * (bloom - sharpHighlight)} (subtract first to avoid clamping the sum).
     * The fx content must already be drawn into {@link #sourceView()} (encoded, {@code 1/HDR_SCALE}).
     */
    public void run(GpuTextureView targetColor) {
        if (mips.length == 0) {
            return;
        }
        var threshold = PhotonConfig.INSTANCE.bloomThreshold.get().floatValue();
        var intensity = PhotonConfig.INSTANCE.bloomIntensity.get().floatValue();

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
        fullscreenPass(blitPipeline(intensity * HDR_SCALE), targetColor, mipViews[0], null);
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
