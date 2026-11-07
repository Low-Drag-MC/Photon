package com.lowdragmc.photon.client.fx.timeline;

import java.util.List;

/**
 * A named stretch of the timeline (like a montage section): from {@link #start()} to the next section's start, or the
 * content end for the last one. It plays {@link #repeat()} times, then goes where {@link #then()} says.
 */
public class TimelineSection {
    /** {@link #repeat()} value: repeat until something moves the clock on. */
    public static final int FOREVER = 0;

    public enum Then {
        /** Carry on into the next section. */
        LINEAR,
        END,
        /** Jump to the section named {@link #target()}. */
        GOTO
    }

    private String name;
    private double start;
    private int repeat = 1;
    private Then then = Then.LINEAR;
    private String target = "";

    public TimelineSection(String name, double start) {
        this.name = name == null ? "" : name;
        this.start = start;
    }

    public String name() {
        return name;
    }

    public TimelineSection name(String name) {
        this.name = name == null ? "" : name;
        return this;
    }

    public double start() {
        return start;
    }

    public TimelineSection start(double start) {
        this.start = start;
        return this;
    }

    public int repeat() {
        return repeat;
    }

    public TimelineSection repeat(int repeat) {
        this.repeat = Math.max(FOREVER, repeat);
        return this;
    }

    public Then then() {
        return then;
    }

    public TimelineSection then(Then then) {
        this.then = then;
        return this;
    }

    public String target() {
        return target;
    }

    public TimelineSection target(String target) {
        this.target = target == null ? "" : target;
        return this;
    }

    public TimelineSection copy() {
        return new TimelineSection(name, start).repeat(repeat).then(then).target(target);
    }

    /** Ripple-insert {@code delta} ticks at {@code atTick}: sections starting at or after it move. */
    public static void insertTime(List<TimelineSection> sections, double atTick, double delta) {
        for (var section : sections) {
            if (section.start >= atTick) {
                section.start += delta;
            }
        }
    }
}
