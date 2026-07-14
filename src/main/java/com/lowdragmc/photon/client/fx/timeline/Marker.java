package com.lowdragmc.photon.client.fx.timeline;

import java.util.List;

/**
 * A timeline marker: a named point on the master clock used as a snapping aid (clip / keyframe drags
 * snap to it) and a visual bookmark on the pinned marker strip. Markers carry no playback behaviour.
 * <p>
 * Kept free of any {@code net.minecraft} dependency (like {@link Clip}) so the ripple-insert logic is
 * unit-testable; (de)serialization lives in {@link Timeline}.
 */
public class Marker {
    private double tick;
    private String name;

    public Marker() {
        this(0, "");
    }

    public Marker(double tick, String name) {
        this.tick = tick;
        this.name = name == null ? "" : name;
    }

    public double tick() {
        return tick;
    }

    public Marker tick(double tick) {
        this.tick = tick;
        return this;
    }

    public String name() {
        return name;
    }

    public Marker name(String name) {
        this.name = name == null ? "" : name;
        return this;
    }

    /** An independent value copy of this marker. */
    public Marker copy() {
        return new Marker(tick, name);
    }

    /** Ripple-insert {@code delta} ticks at {@code atTick}: every marker at or after it shifts later. */
    public static void insertTime(List<Marker> markers, double atTick, double delta) {
        for (var marker : markers) {
            if (marker.tick >= atTick) {
                marker.tick += delta;
            }
        }
    }
}
