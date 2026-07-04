package com.lowdragmc.photon.client.fx.timeline;

/**
 * A time window {@code [start, start + duration)} on a lane — the shared shape of the animation sub-clips
 * ({@link ExprClip}, {@link GradientClip}, {@link CurveClip}) so the editor can move / overlap-test them
 * generically instead of one branch per clip type.
 */
public interface SubClip {
    double start();

    SubClip start(double v);

    double duration();

    double end();
}
