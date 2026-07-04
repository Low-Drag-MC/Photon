package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.lowdraglib2.math.GradientColor;

/**
 * A time window {@code [start, end)} on a color lane during which the color property emits this gradient
 * (a real per-particle {@code Gradient}) instead of the static keyframe-stop color. MC-lite like
 * {@link ExprClip}: the gradient itself is an LDLib2 {@link GradientColor}. Earliest clip wins on overlap.
 */
public class GradientClip implements SubClip {
    private double start;
    private double duration;
    private final GradientColor gradient;

    public GradientClip(double start, double duration, GradientColor gradient) {
        this.start = start;
        this.duration = duration;
        this.gradient = gradient;
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

    public GradientColor gradient() {
        return gradient;
    }

    public GradientClip start(double v) {
        this.start = v;
        return this;
    }

    public GradientClip duration(double v) {
        this.duration = v;
        return this;
    }

    public boolean contains(double time) {
        return time >= start && time < end();
    }

    public GradientClip copy() {
        return new GradientClip(start, duration, gradient == null ? null : gradient.copy());
    }
}
