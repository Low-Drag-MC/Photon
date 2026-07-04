package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;

/**
 * A time window {@code [start, end)} on a config NUMBER_FUNCTION / NUMBER_FUNCTION3 channel during which
 * the channel's runtime value becomes this clip's real {@link NumberFunction} (a {@code Curve}, sampled
 * per-particle over the consumer's own {@code t}) instead of the static keyframe value. MC-lite like
 * {@link ExprClip}/{@link GradientClip}. Earliest clip wins on overlap.
 */
public class CurveClip {
    private double start;
    private double duration;
    private NumberFunction curve;

    public CurveClip(double start, double duration, NumberFunction curve) {
        this.start = start;
        this.duration = duration;
        this.curve = curve;
    }

    public CurveClip curve(NumberFunction curve) {
        this.curve = curve;
        return this;
    }

    public double start() {
        return start;
    }

    public double duration() {
        return duration;
    }

    public double end() {
        return start + duration;
    }

    public NumberFunction curve() {
        return curve;
    }

    public CurveClip start(double v) {
        this.start = v;
        return this;
    }

    public CurveClip duration(double v) {
        this.duration = v;
        return this;
    }

    public boolean contains(double time) {
        return time >= start && time < end();
    }

    public CurveClip copy() {
        return new CurveClip(start, duration, curve == null ? null : curve.copy());
    }
}
