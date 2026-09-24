package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonConfig;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 1.21 bloom mip chain in RGBA16F: bright pass → down-sample → additive up-sample → composite as
 * {@code main + I*(bloom - sharpHighlight)} (a REVERSE_SUBTRACT draw plus an additive one). One instance per size.
 */
public final class PhotonBloom implements AutoCloseable {

    private static final int TEXTURE_USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;

    // ---- per-size instances ----------------------------------------------------------------------

    private static final Map<Long, PhotonBloom> INSTANCES = new ConcurrentHashMap<>();
    private static long frameCounter;

    public static PhotonBloom acquire(int width, int height) {
        var key = ((long) width << 32) | (height & 0xFFFFFFFFL);
        var bloom = INSTANCES.computeIfAbsent(key, k -> create(width, height));
        bloom.lastUsedFrame = frameCounter;
        return bloom;
    }

    private static PhotonBloom create(int width, int height) {
        var source = chainTexture("Photon bloom source", Math.max(1, width), Math.max(1, height));
        var levels = Math.clamp(PhotonConfig.INSTANCE.bloomMipLevel.get(), 1, 10);
        var mips = new ArrayList<GpuTexture>();
        var w = Math.max(1, width / 2);
        var h = Math.max(1, height / 2);
        for (int i = 0; i < levels && w >= 8 && h >= 8; i++) {
            mips.add(chainTexture("Photon bloom mip", w, h));
            w /= 2;
            h /= 2;
        }
        return new PhotonBloom(source, mips.toArray(new GpuTexture[0]));
    }

    private static GpuTexture chainTexture(String label, int width, int height) {
        return RenderSystem.getDevice().createTexture(() -> label, TEXTURE_USAGE, PhotonPipelines.HDR_FORMAT,
                width, height, 1, 1);
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

    private static final BlendFunction ADDITIVE = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE);
    /** {@code dst - src}: takes the sharp highlight back out before its blurred energy is added. */
    private static final BlendFunction REVERSE_SUBTRACT =
            new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.REVERSE_SUBTRACT);

    /** Composites keep the target's alpha: it is coverage for the editor scene and the FX layer. */
    private static ColorTargetState rgbOnly(BlendFunction blend) {
        return new ColorTargetState(Optional.of(blend), PhotonPipelines.HDR_FORMAT, ColorTargetState.WRITE_COLOR);
    }

    private static final Map<Float, RenderPipeline> BRIGHT_VARIANTS = new HashMap<>();
    private static final Map<Float, RenderPipeline> BLIT_VARIANTS = new HashMap<>();
    @Nullable
    private static RenderPipeline downPipeline;
    @Nullable
    private static RenderPipeline upPipeline;

    private static final BindGroupLayout INPUT = BindGroupLayout.builder().withSampler("inputSampler").build();
    private static final BindGroupLayout INPUT_AND_PARAMS = BindGroupLayout.builder()
            .withSampler("inputSampler")
            .withUniform("PhotonBloom", UniformType.UNIFORM_BUFFER)
            .build();

    private static RenderPipeline.Builder fullscreenBuilder(String fragment, BindGroupLayout layout) {
        return PhotonFullscreenPass.builder(Photon.id("core/" + fragment)).withBindGroupLayout(layout);
    }

    /** {@code outputScale} 1 writes mip 0; otherwise the intensity-scaled sharp-highlight subtract. */
    private static RenderPipeline brightPipeline(float outputScale) {
        return BRIGHT_VARIANTS.computeIfAbsent(outputScale, scale ->
                fullscreenBuilder("bright_pass", INPUT_AND_PARAMS)
                        .withLocation(Photon.id("pipeline/bloom_bright_" + BRIGHT_VARIANTS.size()))
                        .withShaderDefine("OUTPUT_SCALE", scale)
                        .withColorTargetState(scale == 1f
                                ? PhotonFullscreenPass.opaqueTarget(PhotonPipelines.HDR_FORMAT)
                                : rgbOnly(REVERSE_SUBTRACT))
                        .build());
    }

    private static RenderPipeline blitPipeline(float outputScale) {
        return BLIT_VARIANTS.computeIfAbsent(outputScale, scale ->
                fullscreenBuilder("bloom_blit", INPUT)
                        .withLocation(Photon.id("pipeline/bloom_blit_" + BLIT_VARIANTS.size()))
                        .withShaderDefine("OUTPUT_SCALE", scale)
                        .withColorTargetState(rgbOnly(ADDITIVE))
                        .build());
    }

    private static RenderPipeline downPipeline() {
        if (downPipeline == null) {
            downPipeline = fullscreenBuilder("down_sampling", INPUT_AND_PARAMS)
                    .withLocation(Photon.id("pipeline/bloom_down"))
                    .withColorTargetState(PhotonFullscreenPass.opaqueTarget(PhotonPipelines.HDR_FORMAT))
                    .build();
        }
        return downPipeline;
    }

    private static RenderPipeline upPipeline() {
        if (upPipeline == null) {
            upPipeline = fullscreenBuilder("up_sampling", INPUT_AND_PARAMS)
                    .withLocation(Photon.id("pipeline/bloom_up"))
                    .withColorTargetState(new ColorTargetState(Optional.of(ADDITIVE), PhotonPipelines.HDR_FORMAT,
                            ColorTargetState.WRITE_ALL))
                    .build();
        }
        return upPipeline;
    }

    // ---- PhotonBloom UBO slices (the 1.21 loose uniforms; per-file block contents) ---------------

    /** 1.21 never set Knee in code — the shader-JSON default 0.1 was the live value. */
    private static final float KNEE = 0.1f;
    /** 1.21 set filterRadius to the constant 0.005 for the whole up-sample chain. */
    private static final float FILTER_RADIUS = 0.005f;

    private static final Map<Float, GpuBufferSlice> BRIGHT_PARAMS = new HashMap<>();
    private static final Map<Long, GpuBufferSlice> RESOLUTION_PARAMS = new HashMap<>();
    @Nullable
    private static GpuBufferSlice radiusParams;

    private static GpuBufferSlice paramsSlice(String label, float... values) {
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
    private static GpuBufferSlice brightParams(float threshold) {
        return BRIGHT_PARAMS.computeIfAbsent(threshold, t -> paramsSlice("Photon bloom bright", KNEE, t));
    }

    /** down_sampling block: {@code vec2 inputResolution;} — the INPUT texture's size (1.21 set it per step). */
    private static GpuBufferSlice resolutionParams(int width, int height) {
        return RESOLUTION_PARAMS.computeIfAbsent(((long) width << 32) | (height & 0xFFFFFFFFL),
                key -> paramsSlice("Photon bloom resolution", width, height));
    }

    /** up_sampling block: {@code float filterRadius;}. */
    private static GpuBufferSlice radiusParams() {
        if (radiusParams == null) {
            radiusParams = paramsSlice("Photon bloom radius", FILTER_RADIUS);
        }
        return radiusParams;
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
        PhotonFullscreenPass.copy("Photon bloom source", targetColor, sourceView, null);

        fullscreenPass(brightPipeline(1f), mipViews[0], sourceView, brightParams(threshold));
        for (int i = 1; i < mips.length; i++) {
            fullscreenPass(downPipeline(), mipViews[i], mipViews[i - 1],
                    resolutionParams(mips[i - 1].getWidth(0), mips[i - 1].getHeight(0)));
        }
        for (int i = mips.length - 2; i >= 0; i--) {
            fullscreenPass(upPipeline(), mipViews[i], mipViews[i + 1], radiusParams());
        }
        // subtract the sharp highlight (its blurred energy arrives via the bloom add below)
        fullscreenPass(brightPipeline(intensity), targetColor, sourceView, brightParams(threshold));
        fullscreenPass(blitPipeline(intensity), targetColor, mipViews[0], null);
    }

    private static void fullscreenPass(RenderPipeline pipeline, GpuTextureView target, GpuTextureView input,
                                       @Nullable GpuBufferSlice params) {
        PhotonFullscreenPass.draw("Photon bloom", pipeline, target, pass -> {
            pass.bindTexture("inputSampler", input,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            if (params != null) {
                pass.setUniform("PhotonBloom", params);
            }
        });
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
