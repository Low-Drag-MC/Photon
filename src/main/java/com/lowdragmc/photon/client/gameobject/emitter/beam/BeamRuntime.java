package com.lowdragmc.photon.client.gameobject.emitter.beam;

import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;

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

    public final RuntimeValue<Integer> duration;
    public final RuntimeValue<Boolean> looping;
    public final RuntimeValue<Integer> startDelay;
    public final RuntimeValue<NumberFunction> width;
    public final RuntimeValue<NumberFunction> emitRate;
    public final RuntimeValue<NumberFunction> color;

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
    }
}
