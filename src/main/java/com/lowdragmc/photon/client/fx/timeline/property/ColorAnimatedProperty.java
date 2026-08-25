package com.lowdragmc.photon.client.fx.timeline.property;

import com.lowdragmc.photon.client.fx.timeline.AnimatedProperty;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.GradientClip;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import net.minecraft.util.Mth;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link AnimatedProperty} whose value is an ARGB <b>color over the master-clock timeline</b>: a list
 * of {@link ColorKey} stops at absolute ticks, each holding an ARGB color. Sampling at a tick linearly
 * interpolates the surrounding stops per channel ({@code f(t) -> ARGB}); outside the first/last stop it
 * holds the edge color. It has <b>no bezier channels</b> (channel count 0) — the gradient replaces them.
 * <p>
 * The sampled color is written to the target as a <b>static</b> {@code NumberFunction.color(argb)} by
 * {@link ColorPropertyType} (computed once per tick), so per-particle reads never re-sample a gradient.
 * <p>
 * When the bound slot takes HDR colours ({@link #isHDR()}) each stop additionally carries an
 * {@link ColorKey#intensity}, and the emitted function is the HDR counterpart
 * ({@link HDRConstantColor} / {@link HDRGradient}) so the range above 1 survives.
 */
public class ColorAnimatedProperty extends AnimatedProperty {

    /** A single color stop: an ARGB color anchored at an absolute timeline tick. */
    public static final class ColorKey {
        public float tick;
        public int argb;
        /** HDR multiplier on rgb; 1 for an LDR property (see {@link ColorAnimatedProperty#isHDR()}). */
        public float intensity;

        public ColorKey(float tick, int argb) {
            this(tick, argb, 1f);
        }

        public ColorKey(float tick, int argb, float intensity) {
            this.tick = tick;
            this.argb = argb;
            this.intensity = intensity;
        }

        public ColorKey copy() {
            return new ColorKey(tick, argb, intensity);
        }
    }

    /** Stops kept sorted ascending by {@link ColorKey#tick}. */
    private final List<ColorKey> stops = new ArrayList<>();
    /** Gradient clips: while the master time is inside a clip, the property emits that clip's real
     *  {@link Gradient} (per-particle) instead of the static stop color. Earliest clip wins on overlap. */
    private final List<GradientClip> gradientClips = new ArrayList<>();
    /**
     * Whether the bound slot takes HDR colour functions — decides which function type {@link
     * #sampleFunction} emits and whether the editor offers an intensity. Refreshed from the target on
     * every {@link #apply}, and persisted so the editor is correct before the first apply.
     */
    private boolean hdr;

    public boolean isHDR() {
        return hdr;
    }

    public void setHDR(boolean hdr) {
        this.hdr = hdr;
    }

    public ColorAnimatedProperty(AnimatedPropertyType type) {
        // no bezier channels: the gradient stops own the value entirely
        super(type, new float[0], new ECBCurves[0], 0, 1);
    }

    public ColorAnimatedProperty(AnimatedPropertyType type, List<ColorKey> stops) {
        this(type);
        for (var k : stops) this.stops.add(k.copy());
        sort();
    }

    /** The (mutable, tick-sorted) list of color stops. */
    public List<ColorKey> stops() {
        return stops;
    }

    public void sort() {
        stops.sort((a, b) -> Float.compare(a.tick, b.tick));
    }

    /** Add a stop, keeping the list tick-sorted; returns the created stop. */
    public ColorKey addStop(float tick, int argb) {
        return addStop(tick, argb, 1f);
    }

    public ColorKey addStop(float tick, int argb, float intensity) {
        var key = new ColorKey(tick, argb, intensity);
        stops.add(key);
        sort();
        return key;
    }

    /** Remove a stop (never removes the last remaining one). */
    public void removeStop(ColorKey key) {
        if (stops.size() > 1) stops.remove(key);
    }

    /** Sample the ARGB color at {@code time} (ticks): edge-clamped, linearly interpolated between stops. */
    public int sampleColor(float time) {
        if (stops.isEmpty()) return 0xFFFFFFFF;
        if (stops.size() == 1) return stops.getFirst().argb;
        var first = stops.getFirst();
        var last = stops.getLast();
        if (time <= first.tick) return first.argb;
        if (time >= last.tick) return last.argb;
        for (int i = 0; i < stops.size() - 1; i++) {
            var a = stops.get(i);
            var b = stops.get(i + 1);
            if (time >= a.tick && time <= b.tick) {
                var span = b.tick - a.tick;
                var f = span <= 0 ? 0f : (time - a.tick) / span;
                return lerpArgb(a.argb, b.argb, f);
            }
        }
        return last.argb;
    }

    /** Sample the HDR intensity at {@code time} (ticks), matching {@link #sampleColor}'s interpolation. */
    public float sampleIntensity(float time) {
        if (stops.isEmpty()) return 1f;
        if (stops.size() == 1) return stops.getFirst().intensity;
        var first = stops.getFirst();
        var last = stops.getLast();
        if (time <= first.tick) return first.intensity;
        if (time >= last.tick) return last.intensity;
        for (int i = 0; i < stops.size() - 1; i++) {
            var a = stops.get(i);
            var b = stops.get(i + 1);
            if (time >= a.tick && time <= b.tick) {
                var span = b.tick - a.tick;
                var f = span <= 0 ? 0f : (time - a.tick) / span;
                return Mth.lerp(f, a.intensity, b.intensity);
            }
        }
        return last.intensity;
    }

    /**
     * A two-stop gradient seeded with this property's sampled colour at {@code tick} — what a new
     * gradient clip starts from. In HDR mode the stops are premultiplied by the sampled intensity, which
     * is the storage convention {@link HDRGradient} expects.
     */
    public GradientColor sampledGradient(float tick) {
        var argb = sampleColor(tick);
        var gradient = new GradientColor(argb, argb);
        if (hdr) {
            var intensity = sampleIntensity(tick);
            // 26.1 hands the stops out as Vector4fc — write mutable copies back rather than mutating
            // through the read-only view (the gradient is freshly built here, so nothing else sees them)
            var stops = gradient.getRgbP();
            for (int i = 0; i < stops.size(); i++) {
                var stop = stops.get(i);
                stops.set(i, new Vector4f(stop.x(), stop.y() * intensity,
                        stop.z() * intensity, stop.w() * intensity));
            }
        }
        return gradient;
    }

    /** Per-channel linear interpolation of two ARGB colors (rounded). */
    public static int lerpArgb(int c0, int c1, float f) {
        var a = Math.round(((c0 >> 24) & 0xFF) + (((c1 >> 24) & 0xFF) - ((c0 >> 24) & 0xFF)) * f);
        var r = Math.round(((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) - ((c0 >> 16) & 0xFF)) * f);
        var g = Math.round(((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) - ((c0 >> 8) & 0xFF)) * f);
        var b = Math.round((c0 & 0xFF) + ((c1 & 0xFF) - (c0 & 0xFF)) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** The (mutable) list of gradient clips. */
    public List<GradientClip> gradientClips() {
        return gradientClips;
    }

    /** The gradient clip containing {@code time}, earliest wins on overlap, or {@code null}. */
    @Nullable
    public GradientClip activeGradientClip(double time) {
        for (var clip : gradientClips) {
            if (clip.contains(time)) return clip;
        }
        return null;
    }

    /** The runtime {@link NumberFunction} at {@code time}: an active gradient clip's real {@link Gradient}
     *  (per-particle), else the static stop color as {@code NumberFunction.color(argb)}. */
    public NumberFunction sampleFunction(float time) {
        var clip = activeGradientClip(time);
        if (clip != null && clip.gradient() != null) {
            return hdr ? new HDRGradient(clip.gradient().copy()) : new Gradient(clip.gradient().copy());
        }
        if (hdr) {
            return new HDRConstantColor(HDRColor.fromARGB(sampleColor(time)).withIntensity(sampleIntensity(time)));
        }
        return NumberFunction.color(sampleColor(time));
    }

    public List<GradientClip> snapshotGradientClips() {
        var copy = new ArrayList<GradientClip>();
        for (var c : gradientClips) copy.add(c.copy());
        return copy;
    }

    public void restoreGradientClips(List<GradientClip> snapshot) {
        // Count match (a move/resize): mutate start/duration in place so UI element references stay valid
        // across a drag/undo (mirrors AnimatedProperty#restoreExprClips). Only a structural change rebuilds.
        if (gradientClips.size() == snapshot.size()) {
            for (int i = 0; i < gradientClips.size(); i++) {
                gradientClips.get(i).start(snapshot.get(i).start()).duration(snapshot.get(i).duration());
            }
        } else {
            gradientClips.clear();
            for (var c : snapshot) gradientClips.add(c.copy());
        }
    }

    @Override
    public void insertTime(double atTick, double delta) {
        super.insertTime(atTick, delta); // no bezier channels, but shifts nothing (count 0) — safe
        for (var stop : stops) {
            if (stop.tick >= atTick) stop.tick += (float) delta;
        }
        sort();
        for (var clip : gradientClips) shiftSubClip(clip, atTick, delta);
    }

    @Override
    public void apply(FXObject target, double time) {
        if (type() instanceof ColorPropertyType color) {
            // re-read from the target: the bound stream may have been switched between LDR and HDR since
            // this property was created/loaded
            hdr = color.isHDR(target);
            color.applyFunction(target, sampleFunction((float) time));
        }
    }

    /** Stop ticks (drives the collapsed-lane dots, content extent and snapping). */
    @Override
    public List<Double> keyframeTimes() {
        var times = new ArrayList<Double>();
        for (var k : stops) times.add((double) k.tick);
        return times;
    }

    @Override
    public ColorAnimatedProperty copy() {
        var copy = new ColorAnimatedProperty(type(), stops);
        copy.restoreGradientClips(gradientClips);
        copy.hdr = hdr;
        return copy;
    }

    /** Deep copy of the stops, for undo snapshots. */
    public List<ColorKey> snapshotStops() {
        var copy = new ArrayList<ColorKey>();
        for (var k : stops) copy.add(k.copy());
        return copy;
    }

    /** Restore the stops from a {@link #snapshotStops()} snapshot. */
    public void restoreStops(List<ColorKey> snapshot) {
        stops.clear();
        for (var k : snapshot) stops.add(k.copy());
        sort();
    }

    @Override
    public void restoreFrom(AnimatedProperty other) {
        super.restoreFrom(other);
        if (other instanceof ColorAnimatedProperty color) {
            restoreStops(color.stops);
            restoreGradientClips(color.gradientClips);
            hdr = color.hdr;
        }
    }
}
