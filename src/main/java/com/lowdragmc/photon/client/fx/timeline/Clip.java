package com.lowdragmc.photon.client.fx.timeline;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * A single clip on a timeline {@link Track}: an active window over the master timeline.
 * <p>
 * This class is intentionally free of any {@code net.minecraft} dependency so the timeline
 * evaluation core can be unit-tested with plain JUnit (the rest of the FX runtime is
 * client-only and cannot be reached from JUnit). Serialization lives in {@link Track}.
 */
public class Clip {
    private double start;
    private double duration;
    /**
     * Raw authored playback speed of the bound object while inside this clip. The data model
     * carries arbitrary values, but Phase 1a clamps the engine to {0 (frozen), 1 (normal)} via
     * {@link #phase1aSpeed(float)}. Phase 1b will honor fractional values directly.
     */
    private float speed;
    /** Control-track clips bind an FXObject (its transform id); {@code null} for activator clips. */
    @Nullable
    private UUID targetId;
    /** Random seed applied to the bound object when a control clip restarts it. */
    private long seed;
    /** When true, a fresh random seed is used on each restart instead of {@link #seed}. */
    private boolean randomSeed;

    public Clip() {
        this(0.0, 1.0, 1.0f);
    }

    public Clip(double start, double duration, float speed) {
        this.start = start;
        this.duration = duration;
        this.speed = speed;
    }

    @Nullable
    public UUID targetId() {
        return targetId;
    }

    public Clip targetId(@Nullable UUID targetId) {
        this.targetId = targetId;
        return this;
    }

    public long seed() {
        return seed;
    }

    public Clip seed(long seed) {
        this.seed = seed;
        return this;
    }

    public boolean randomSeed() {
        return randomSeed;
    }

    public Clip randomSeed(boolean randomSeed) {
        this.randomSeed = randomSeed;
        return this;
    }

    public double start() {
        return start;
    }

    public Clip start(double start) {
        this.start = start;
        return this;
    }

    public double duration() {
        return duration;
    }

    public Clip duration(double duration) {
        this.duration = duration;
        return this;
    }

    public float speed() {
        return speed;
    }

    public Clip speed(float speed) {
        this.speed = speed;
        return this;
    }

    /**
     * An independent value copy of this clip.
     */
    public Clip copy() {
        return new Clip(start, duration, speed).targetId(targetId).seed(seed).randomSeed(randomSeed);
    }

    /**
     * Exclusive end time of this clip.
     */
    public double end() {
        return start + duration;
    }

    /**
     * Whether {@code time} falls within this clip. Half-open {@code [start, end)} so adjacent
     * clips (one ending exactly where the next begins) never both match.
     */
    public boolean contains(double time) {
        return time >= start && time < end();
    }

    /**
     * Time elapsed since this clip's start, clamped to {@code [0, duration]}.
     */
    public double localTime(double time) {
        var local = time - start;
        if (local < 0) return 0;
        if (local > duration) return duration;
        return local;
    }

    /**
     * The active clip at {@code time}, or {@code null} when {@code time} is in a gap. On overlap
     * the earliest clip in {@code clips} wins.
     */
    @Nullable
    public static Clip activeClip(List<Clip> clips, double time) {
        for (var clip : clips) {
            if (clip.contains(time)) {
                return clip;
            }
        }
        return null;
    }

    /**
     * Phase 1a engine clamp: any positive speed runs at normal (1), zero or negative freezes (0).
     */
    public static float phase1aSpeed(float rawSpeed) {
        return rawSpeed > 0 ? 1.0f : 0.0f;
    }

    /**
     * Transition between the previously-active clip and the now-active clip on the same track,
     * used by the timeline player to drive emit/remove of the bound object.
     */
    public enum Transition {
        /** No change (both null, or the same clip is still active). */
        NONE,
        /** Entered a clip from a gap. */
        ENTER,
        /** Left a clip into a gap. */
        EXIT,
        /** Moved directly from one clip to a different clip (exit old + enter new). */
        SWITCH;

        public static Transition between(@Nullable Clip previous, @Nullable Clip current) {
            if (previous == current) return NONE;
            if (previous == null) return ENTER;
            if (current == null) return EXIT;
            return SWITCH;
        }
    }
}
