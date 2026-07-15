package com.lowdragmc.photon.client.gameobject.emitter.beam;

import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomDataRuntime;
import com.lowdragmc.photon.client.gameobject.emitter.data.InstancedRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import org.jetbrains.annotations.Nullable;

/**
 * Per-{@link BeamEmitter}-instance runtime layer. Mirrors {@code ParticleRuntime}: it holds the named
 * {@link RuntimeValue} slots written by the timeline over the immutable {@link BeamConfig}, plus the reused
 * {@code Setting.Runtime} layers for the shared light / uv-animation settings. A slot with no timeline value
 * falls back to the authored config. All reads are direct field access — no map, no reflection.
 */
public class BeamRuntime {
    public final BeamConfig config;
    public final LightOverLifetimeSetting.Runtime lights;
    public final UVAnimationSetting.Runtime uvAnimation;
    /** Per-instance override of the additional-GPU custom-data VALUES (structure stays from config). */
    public final CustomDataRuntime customData;

    public final RuntimeValue<Integer> duration;
    public final RuntimeValue<Boolean> looping;
    public final RuntimeValue<Integer> startDelay;
    public final RuntimeValue<NumberFunction> width;
    public final RuntimeValue<NumberFunction> emitRate;
    public final RuntimeValue<NumberFunction> color;

    /** Co-located per-instance render-override slots (material / renderer settings), batching-safe. */
    public final InstancedRendererSetting.Runtime renderer;
    /** The per-emitter override render pass (lazy; wraps {@link #renderer}), or null while no slot is set. */
    @Nullable private PhotonFXRenderPass overridePass;

    public BeamRuntime(BeamConfig config) {
        this.config = config;
        this.duration = new RuntimeValue<>(config::getDuration);
        this.looping = new RuntimeValue<>(config::isLooping);
        this.startDelay = new RuntimeValue<>(config::getStartDelay);
        this.width = new RuntimeValue<>(config::getWidth);
        this.emitRate = new RuntimeValue<>(config::getEmitRate);
        this.color = new RuntimeValue<>(config::getColor);
        this.lights = config.lights.createRuntime();
        this.uvAnimation = config.uvAnimation.createRuntime();
        this.customData = new CustomDataRuntime(config.additionalGPUDataSetting);
        this.renderer = config.renderer.createRuntime();
    }

    /**
     * The render pass this emitter draws through: its per-instance override pass when any render slot is
     * overridden (lazily built to wrap {@link #renderer}), else the shared {@code config.particleRenderType}.
     */
    public PhotonFXRenderPass effectiveRenderPass() {
        if (renderer.hasOverride()) {
            if (overridePass == null) {
                overridePass = config.createRenderPass(renderer);
            }
            return overridePass;
        }
        if (overridePass != null) {
            overridePass.clearInstance();
            overridePass = null;
        }
        return config.particleRenderType;
    }

    /** Whether this emitter currently has any render-override slot set. */
    public boolean hasRenderOverride() {
        return renderer.hasOverride();
    }

    /** Drop every render-override slot (revert to the shared pass) and free the override pass's GL. */
    public void clearRenderOverride() {
        renderer.clear();
        if (overridePass != null) {
            overridePass.clearInstance();
            overridePass = null;
        }
    }

    /** Clear every timeline override (fall back to authored config). Called on emitter reset. */
    public void clear() {
        duration.clear();
        looping.clear();
        startDelay.clear();
        width.clear();
        emitRate.clear();
        color.clear();
        lights.clear();
        uvAnimation.clear();
        customData.clear();
        clearRenderOverride();
    }
}
