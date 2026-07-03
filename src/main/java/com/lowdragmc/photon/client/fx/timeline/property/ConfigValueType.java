package com.lowdragmc.photon.client.fx.timeline.property;

/**
 * The value kind of a timeline-animatable config value (declared by a {@link RuntimeBinding}), driving
 * channel count, sampling (continuous bezier vs discrete step) and quantization.
 * <ul>
 *     <li>{@link #INT} / {@link #BOOL} are <b>discrete</b>: step (hold) interpolation, snapped values.</li>
 *     <li>{@link #FLOAT} / {@link #NUMBER_FUNCTION} are single continuous channels.</li>
 *     <li>{@link #NUMBER_FUNCTION3} is three continuous channels (x/y/z).</li>
 * </ul>
 */
public enum ConfigValueType {
    INT,
    BOOL,
    FLOAT,
    NUMBER_FUNCTION,
    NUMBER_FUNCTION3;

    public int channelCount() {
        return this == NUMBER_FUNCTION3 ? 3 : 1;
    }

    /** Discrete types use step interpolation + value snapping instead of smooth bezier curves. */
    public boolean discrete() {
        return this == INT || this == BOOL;
    }
}
