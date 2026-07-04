package com.lowdragmc.photon.client.gameobject.emitter.aratrail;

import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;

/**
 * Per-{@link AraTrailEmitter}-instance runtime layer. Mirrors {@code ParticleRuntime}: it holds the named
 * {@link RuntimeValue} slots written by the timeline over the immutable {@link AraTrailConfig}, plus the
 * {@link AraPhysicsSetting.Runtime} layer for the physics sub-setting. A slot with no timeline value falls
 * back to the authored config. All reads are direct field access — no map, no reflection.
 */
public class AraTrailRuntime {
    public final AraTrailConfig config;
    public final AraPhysicsSetting.Runtime physics;

    public final RuntimeValue<Integer> duration;
    public final RuntimeValue<Boolean> looping;
    public final RuntimeValue<Float> thickness;
    public final RuntimeValue<NumberFunction> thicknessOverLength;
    public final RuntimeValue<NumberFunction> colorOverLength;
    public final RuntimeValue<NumberFunction> thicknessOverTime;
    public final RuntimeValue<NumberFunction> thicknessOverSegmentTime;
    public final RuntimeValue<NumberFunction> colorOverTime;
    public final RuntimeValue<NumberFunction> colorOverSegmentTime;
    public final RuntimeValue<Float> initialThickness;
    public final RuntimeValue<Float> time;
    public final RuntimeValue<Float> minDistance;
    public final RuntimeValue<Float> timeInterval;

    public AraTrailRuntime(AraTrailConfig config) {
        this.config = config;
        this.duration = new RuntimeValue<>(() -> config.duration);
        this.looping = new RuntimeValue<>(() -> config.looping);
        this.thickness = new RuntimeValue<>(() -> config.thickness);
        this.thicknessOverLength = new RuntimeValue<>(() -> config.thicknessOverLength);
        this.colorOverLength = new RuntimeValue<>(() -> config.colorOverLength);
        this.thicknessOverTime = new RuntimeValue<>(() -> config.thicknessOverTime);
        this.thicknessOverSegmentTime = new RuntimeValue<>(() -> config.thicknessOverSegmentTime);
        this.colorOverTime = new RuntimeValue<>(() -> config.colorOverTime);
        this.colorOverSegmentTime = new RuntimeValue<>(() -> config.colorOverSegmentTime);
        this.initialThickness = new RuntimeValue<>(() -> config.initialThickness);
        this.time = new RuntimeValue<>(() -> config.time);
        this.minDistance = new RuntimeValue<>(() -> config.minDistance);
        this.timeInterval = new RuntimeValue<>(() -> config.timeInterval);
        this.physics = config.physicsSetting.createRuntime();
    }

    /** Clear every timeline override (fall back to authored config). Called on emitter reset. */
    public void clear() {
        duration.clear();
        looping.clear();
        thickness.clear();
        thicknessOverLength.clear();
        colorOverLength.clear();
        thicknessOverTime.clear();
        thicknessOverSegmentTime.clear();
        colorOverTime.clear();
        colorOverSegmentTime.clear();
        initialThickness.clear();
        time.clear();
        minDistance.clear();
        timeInterval.clear();
        physics.clear();
    }
}
