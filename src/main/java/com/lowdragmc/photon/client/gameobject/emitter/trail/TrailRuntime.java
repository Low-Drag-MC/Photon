package com.lowdragmc.photon.client.gameobject.emitter.trail;

import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;

/**
 * Per-{@link TrailEmitter}-instance runtime layer. Mirrors {@code ParticleRuntime}: it holds the named
 * {@link RuntimeValue} slots written by the timeline over the immutable {@link TrailConfig}, plus the reused
 * {@code Setting.Runtime} layers for the shared light / uv-animation settings. A slot with no timeline value
 * falls back to the authored config. All reads are direct field access — no map, no reflection.
 */
public class TrailRuntime {
    public final TrailConfig config;
    public final LightOverLifetimeSetting.Runtime lights;
    public final UVAnimationSetting.Runtime uvAnimation;

    public final RuntimeValue<Integer> duration;
    public final RuntimeValue<Boolean> looping;
    public final RuntimeValue<Integer> startDelay;
    public final RuntimeValue<Integer> time;
    public final RuntimeValue<Float> minVertexDistance;
    public final RuntimeValue<NumberFunction> widthOverTrail;
    public final RuntimeValue<NumberFunction> colorOverTrail;

    public TrailRuntime(TrailConfig config) {
        this.config = config;
        this.duration = new RuntimeValue<>(config::getDuration);
        this.looping = new RuntimeValue<>(config::isLooping);
        this.startDelay = new RuntimeValue<>(config::getStartDelay);
        this.time = new RuntimeValue<>(config::getTime);
        this.minVertexDistance = new RuntimeValue<>(config::getMinVertexDistance);
        this.widthOverTrail = new RuntimeValue<>(config::getWidthOverTrail);
        this.colorOverTrail = new RuntimeValue<>(config::getColorOverTrail);
        this.lights = config.lights.createRuntime();
        this.uvAnimation = config.uvAnimation.createRuntime();
    }

    /** Clear every timeline override (fall back to authored config). Called on emitter reset. */
    public void clear() {
        duration.clear();
        looping.clear();
        startDelay.clear();
        time.clear();
        minVertexDistance.clear();
        widthOverTrail.clear();
        colorOverTrail.clear();
        lights.clear();
        uvAnimation.clear();
    }
}
