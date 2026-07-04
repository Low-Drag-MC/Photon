package com.lowdragmc.photon.client.fx.timeline.property;

/**
 * The value kind of a timeline-animatable config value (declared by a {@link com.lowdragmc.photon.client.gameobject.RuntimeBinding}), driving
 * channel count, sampling (continuous bezier vs discrete step) and quantization.
 * <ul>
 *     <li>{@link #INT} / {@link #BOOL} are <b>discrete</b>: step (hold) interpolation, snapped values.</li>
 *     <li>{@link #FLOAT} / {@link #NUMBER_FUNCTION} are single continuous channels.</li>
 *     <li>{@link #NUMBER_FUNCTION3} is three continuous channels (x/y/z).</li>
 *     <li>{@link #COLOR} is a gradient over the timeline (no bezier channels); see
 *     {@code ColorAnimatedProperty}.</li>
 * </ul>
 */
public enum ConfigValueType {
    INT,
    BOOL,
    FLOAT,
    NUMBER_FUNCTION,
    NUMBER_FUNCTION3,
    COLOR;

    public int channelCount() {
        return switch (this) {
            case NUMBER_FUNCTION3 -> 3;
            case COLOR -> 0; // color uses a gradient of stops, not bezier channels
            default -> 1;
        };
    }

    /** Discrete types use step interpolation + value snapping instead of smooth bezier curves. */
    public boolean discrete() {
        return this == INT || this == BOOL;
    }
}
