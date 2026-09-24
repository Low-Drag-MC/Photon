package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/**
 * Pre-FX copies of the current target's colour/depth for {@code SamplerScene*} and the wireframe overlay, taken
 * on demand per stage. Needs {@code COPY_SRC} on the source; colour falls back to a sampling draw, depth has no
 * fallback. Outside a render pass only.
 */
public final class PhotonSceneCapture {

    private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private static final class Slot {
        final String label;
        @Nullable
        GpuTexture texture;
        @Nullable
        GpuTextureView view;
        int width, height;
        @Nullable
        GpuFormat format;
        boolean warnedUncopyable;

        Slot(String label) {
            this.label = label;
        }

        private void ensure(GpuTexture source) {
            var w = source.getWidth(0);
            var h = source.getHeight(0);
            if (texture == null || w != width || h != height || source.getFormat() != format) {
                if (view != null) {
                    view.close();
                }
                if (texture != null) {
                    texture.close();
                }
                width = w;
                height = h;
                format = source.getFormat();
                var device = RenderSystem.getDevice();
                // depth formats cannot be colour attachments; the depth slot is only ever a copy target
                var usage = format.hasDepthAspect() ? USAGE & ~GpuTexture.USAGE_RENDER_ATTACHMENT : USAGE;
                texture = device.createTexture(() -> label, usage, format, w, h, 1, 1);
                view = device.createTextureView(texture);
            }
        }

        @Nullable
        GpuTextureView capture(GpuTextureView target) {
            var source = target.texture();
            ensure(source);
            if ((source.usage() & GpuTexture.USAGE_COPY_SRC) != 0) {
                RenderSystem.getDevice().createCommandEncoder()
                        .copyTextureToTexture(source, texture, 0, 0, 0, 0, 0, width, height);
                return view;
            }
            if (!format.hasDepthAspect() && (source.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
                PhotonFullscreenPass.copy(label, target, view, null);
                return view;
            }
            if (!warnedUncopyable) {
                warnedUncopyable = true;
                Photon.LOGGER.warn("{}: the target being drawn into ({}) can neither be copied nor sampled — "
                        + "effects reading it get nothing this frame", label, source.getLabel());
            }
            return null;
        }
    }

    private static final Slot COLOR = new Slot("Photon scene capture");
    private static final Slot DEPTH = new Slot("Photon scene depth capture");

    private PhotonSceneCapture() {
    }

    /** Null when the target cannot be copied. */
    @Nullable
    public static GpuTextureView captureColor(GpuTextureView target) {
        return COLOR.capture(target);
    }

    /** Null when the target cannot be copied. */
    @Nullable
    public static GpuTextureView captureDepth(GpuTextureView target) {
        depthCaptures++;
        return DEPTH.capture(target);
    }

    /** Depth copies taken since launch, for {@code SoftParticleScenario}. */
    public static int depthCaptures() {
        return depthCaptures;
    }

    private static int depthCaptures;

    /** For renderers without a scene of their own (material previews). */
    @Nullable
    public static GpuTextureView lastColorView() {
        return COLOR.view;
    }

    @Nullable
    public static GpuTextureView lastDepthView() {
        return DEPTH.view;
    }

    // ---- the 26.1 depth convention, for legacy custom shaders ----------------------------------------

    @Nullable
    private static GpuTexture legacyDepth;
    @Nullable
    private static GpuTextureView legacyDepthView;
    @Nullable
    private static RenderPipeline legacyDepthPipeline;
    @Nullable
    private static GpuTexture legacyStandIn;
    @Nullable
    private static GpuTextureView legacyStandInView;

    /** {@code 1 - depth} in R32F for legacy shaders; pairs with {@code PhotonEngineUniforms.legacySlice()}. */
    public static GpuTextureView legacyDepth(GpuTextureView depthCapture) {
        var source = depthCapture.texture();
        var w = source.getWidth(0);
        var h = source.getHeight(0);
        if (legacyDepth == null || legacyDepth.getWidth(0) != w || legacyDepth.getHeight(0) != h) {
            if (legacyDepthView != null) legacyDepthView.close();
            if (legacyDepth != null) legacyDepth.close();
            var device = RenderSystem.getDevice();
            legacyDepth = device.createTexture(() -> "Photon legacy scene depth",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.R32_FLOAT,
                    w, h, 1, 1);
            legacyDepthView = device.createTextureView(legacyDepth);
        }
        if (legacyDepthPipeline == null) {
            legacyDepthPipeline = PhotonFullscreenPass.builder(Photon.id("core/photon_legacy_depth"))
                    .withLocation(Photon.id("pipeline/legacy_depth"))
                    .withBindGroupLayout(BindGroupLayout.builder()
                            .withSampler("InSampler").build())
                    .withColorTargetState(PhotonFullscreenPass.opaqueTarget(GpuFormat.R32_FLOAT))
                    .build();
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        PhotonFullscreenPass.draw("Photon legacy scene depth", legacyDepthPipeline, legacyDepthView, null,
                pass -> pass.bindTexture("InSampler", depthCapture, nearest));
        return legacyDepthView;
    }

    /** {@link #standInDepth()} in the legacy convention (far = 1). */
    public static GpuTextureView legacyStandInDepth() {
        if (legacyStandInView == null) {
            var device = RenderSystem.getDevice();
            legacyStandIn = device.createTexture(() -> "Photon legacy scene depth stand-in",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.R32_FLOAT, 1, 1, 1, 1);
            legacyStandInView = device.createTextureView(legacyStandIn);
            device.createCommandEncoder().clearColorTexture(legacyStandIn, new Vector4f(1, 1, 1, 1));
        }
        return legacyStandInView;
    }

    // ---- stand-ins -----------------------------------------------------------------------------------

    @Nullable
    private static GpuTexture standInColor;
    @Nullable
    private static GpuTextureView standInColorView;
    @Nullable
    private static GpuTexture standInDepth;
    @Nullable
    private static GpuTextureView standInDepthView;

    /** Transparent black, bound when no capture could be taken; a declared sampler must always be bound. */
    public static GpuTextureView standInColor() {
        if (standInColorView == null) {
            var device = RenderSystem.getDevice();
            standInColor = device.createTexture(() -> "Photon scene colour stand-in",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
            standInColorView = device.createTextureView(standInColor);
            device.createCommandEncoder().clearColorTexture(standInColor, new Vector4f(0, 0, 0, 0));
        }
        return standInColorView;
    }

    /** The far plane everywhere (reverse-Z: 0). */
    public static GpuTextureView standInDepth() {
        if (standInDepthView == null) {
            var device = RenderSystem.getDevice();
            standInDepth = device.createTexture(() -> "Photon scene depth stand-in",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, 1, 1, 1, 1);
            standInDepthView = device.createTextureView(standInDepth);
            device.createCommandEncoder().clearDepthTexture(standInDepth, RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
        }
        return standInDepthView;
    }

    public static GpuSampler sampler() {
        return RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
    }
}
