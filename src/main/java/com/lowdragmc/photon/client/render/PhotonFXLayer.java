package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A standalone <b>premultiplied FX layer</b>: the same RGBA16F storage as {@link PhotonDrawTarget}, but
 * <i>cleared to transparent black</i> instead of seeded with the scene, and merged back with a
 * {@code ONE / ONE_MINUS_SRC_ALPHA} blend instead of a replace.
 *
 * <p>Two features need exactly this and nothing else:
 * <ul>
 *   <li>{@link FXCompositeMode#LATE} — the layer is held back until after clouds and weather, so they
 *       cannot paint over FX that never wrote depth;</li>
 *   <li>a shader pack — the layer is handed to the pack's own translucent target, which is what a
 *       pack's {@code gbuffers_*_translucent} program would have written.</li>
 * </ul>
 *
 * <p>Because the destination is transparent black rather than the scene, the alpha channel stops being
 * a decorative value and becomes <b>coverage</b> — "how much of the backdrop did this layer hide". Every
 * draw into it must therefore go through {@link PremultipliedBlendPlan}; blends that read the destination
 * colour cannot be expressed here at all and are routed away by {@link PremultipliedBlendPlan#isLayerSafe}.
 *
 * <p>Bloom may run over the layer first and that is safe: every bloom pass that writes back into its
 * target declares {@code WRITE_COLOR}, so the coverage this composite reads is exactly what the FX draws
 * left. (1.21 had to carry a second, un-bloomed alpha texture for precisely this reason.)
 *
 * <p>Pooled per size and evicted when unused, exactly like {@link PhotonDrawTarget}. GL backend only
 * (RGBA16F comes from {@link PhotonFloatTextures}). Render thread only.
 */
public final class PhotonFXLayer implements AutoCloseable {

    /**
     * {@code COPY_DST} is required by {@code CommandEncoder.clearColorTexture}, which {@link #beginFrame()}
     * uses — the engine treats a clear as a write into the texture and validates it as one.
     * <p>
     * {@code COPY_SRC} is still deliberately absent, for {@link PhotonDrawTarget}'s reason: declaring it
     * would make {@link PhotonSceneCapture} prefer the engine's {@code copyTextureToTexture}, which
     * calls {@code _disableScissorTest()} and never restores it.
     */
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST;

    private static final Map<Long, PhotonFXLayer> INSTANCES = new ConcurrentHashMap<>();
    private static final Set<Long> FAILED = ConcurrentHashMap.newKeySet();
    private static long frameCounter;

    @Nullable
    private static RenderPipeline compositeWriteAlpha;
    @Nullable
    private static RenderPipeline compositeKeepAlpha;

    /**
     * The layer for an output of this size, or null when RGBA16F is unavailable — the caller must fall
     * back to drawing in place rather than silently losing the effects.
     */
    @Nullable
    public static PhotonFXLayer acquire(int width, int height) {
        var key = ((long) width << 32) | (height & 0xFFFFFFFFL);
        var existing = INSTANCES.get(key);
        if (existing != null) {
            existing.lastUsedFrame = frameCounter;
            return existing;
        }
        if (FAILED.contains(key)) {
            return null;
        }
        var texture = PhotonFloatTextures.createRgba16f("Photon fx layer", USAGE,
                Math.max(1, width), Math.max(1, height));
        if (texture == null) {
            FAILED.add(key);
            Photon.LOGGER.error("Photon could not allocate its {}x{} RGBA16F fx layer — deferred and "
                    + "shader-pack compositing are unavailable at this size", width, height);
            return null;
        }
        var layer = new PhotonFXLayer(texture);
        INSTANCES.put(key, layer);
        layer.lastUsedFrame = frameCounter;
        return layer;
    }

    /**
     * The layer of this size that something already drew into THIS frame, or null. A bare lookup that
     * never allocates — the merge step asks every view, including ones that never layer anything (the
     * editor scene), and {@link #acquire} would hand those a screen-sized RGBA16F for nothing.
     */
    @Nullable
    public static PhotonFXLayer inUse(int width, int height) {
        var layer = INSTANCES.get(((long) width << 32) | (height & 0xFFFFFFFFL));
        return layer != null && layer.usedThisFrame() ? layer : null;
    }

    /** Frame boundary: drop layers nothing rendered into for a while. */
    public static void endFrame() {
        frameCounter++;
        INSTANCES.values().removeIf(layer -> {
            if (frameCounter - layer.lastUsedFrame > 120) {
                layer.close();
                return true;
            }
            return false;
        });
    }

    /**
     * The composite: {@code dst = src + dst * (1 - src.a)}, i.e. exactly what a pack's own translucent
     * program does — which is why an FX layer merged this way looks the same as water blended by the pack.
     * <p>
     * Alpha is written as well as colour, because when the destination is a translucent accumulator its
     * alpha is the coverage the pack's own blend pass reads back. Destinations whose alpha is not ours
     * to touch are handled by the caller passing a masked variant.
     */
    private static RenderPipeline compositePipeline(boolean writeAlpha) {
        if (writeAlpha) {
            if (compositeWriteAlpha == null) {
                compositeWriteAlpha = buildComposite("fx_layer_composite", ColorTargetState.WRITE_ALL);
            }
            return compositeWriteAlpha;
        }
        if (compositeKeepAlpha == null) {
            compositeKeepAlpha = buildComposite("fx_layer_composite_rgb", ColorTargetState.WRITE_COLOR);
        }
        return compositeKeepAlpha;
    }

    private static RenderPipeline buildComposite(String name, int writeMask) {
        return PhotonFullscreenPass.builder(Photon.id("core/fx_layer_composite"))
                .withLocation(Photon.id("pipeline/" + name))
                .withSampler("colorSampler")
                .withColorTargetState(new ColorTargetState(
                        Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), writeMask))
                .build();
    }

    private final GpuTexture texture;
    private final GpuTextureView view;
    private long lastUsedFrame;
    private long clearedFrame = Long.MIN_VALUE;

    private PhotonFXLayer(GpuTexture texture) {
        this.texture = texture;
        this.view = RenderSystem.getDevice().createTextureView(texture);
    }

    /** What the layer's render passes use as their color attachment. */
    public GpuTextureView view() {
        return view;
    }

    /**
     * Reset to transparent black, <b>once per frame</b>. Unlike {@link PhotonDrawTarget#copyFrom} this is
     * a genuine clear — the layer must start with zero coverage, or last frame's FX would be composited
     * again.
     * <p>
     * Once per frame rather than once per drain because every Photon stage of a frame accumulates into
     * the SAME layer before it is merged: clearing again at the translucent stage would throw away
     * whatever the opaque one drew. (1.21 said the same thing as "both queues accumulate into it before
     * it is consumed".)
     *
     * @return true when this call did the clearing, i.e. this is the frame's first use of the layer
     */
    public boolean beginFrame() {
        if (clearedFrame == frameCounter) {
            return false;
        }
        clearedFrame = frameCounter;
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, 0);
        return true;
    }

    /** Whether anything drew into this layer during the current frame. */
    public boolean usedThisFrame() {
        return clearedFrame == frameCounter;
    }

    /**
     * Blend this layer onto {@code destination}.
     *
     * @param writeAlpha  false when the destination's alpha is not ours: MC's main target is already
     *                    opaque, and a pack's scene colour owns that channel. True when the destination
     *                    is a translucent accumulator, whose alpha the pack reads back as coverage.
     */
    public void compositeTo(GpuTextureView destination, boolean writeAlpha) {
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        PhotonFullscreenPass.draw("Photon fx layer composite", compositePipeline(writeAlpha), destination,
                pass -> pass.bindTexture("colorSampler", view, sampler));
    }

    @Override
    public void close() {
        view.close();
        texture.close();
    }
}
