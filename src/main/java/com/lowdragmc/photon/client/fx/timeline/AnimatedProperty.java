package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.lowdraglib2.math.curve.ExplicitCubicBezierCurve2;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A single animatable property of an {@link AnimationTrack}'s target object, described by a registerable
 * {@link AnimatedPropertyType} (transform position/rotation/scale, or any object-specific type). It owns
 * one keyframe curve per channel (e.g. x/y/z), stored in <b>(tick, value)</b> space via {@link ECBCurves}
 * so the bezier keyframes map directly onto the timeline. A single keyframe is a zero-width segment
 * {@code (t,v)->(t,v)}; N keyframes are N-1 segments. The whole property shares one display value range.
 * <p>
 * The value model is <b>absolute, seeded from pose</b>: {@link #base} is the object's value captured when
 * the property was added; the curves are seeded with a single keyframe at that value and from then on
 * fully own the property. {@link #restoreBase} writes {@code base} back when the property is removed/muted.
 */
public class AnimatedProperty {
    /** Interpolation mode for angular ({@link AnimatedPropertyType#angular()}) properties. */
    public static final int INTERP_DEFAULT = 0;
    public static final int INTERP_SHORTEST = 1;

    private final AnimatedPropertyType type;
    private final float[] base;          // captured authored value, one per channel
    private final ECBCurves[] channels;  // one keyframe curve per channel
    private float rangeMin;              // shared display value range (one Y axis for all channels)
    private float rangeMax;
    private int interpMode = INTERP_DEFAULT;

    private AnimatedProperty(AnimatedPropertyType type, float[] base, ECBCurves[] channels,
                            float rangeMin, float rangeMax, int interpMode) {
        this.type = type;
        this.base = base;
        this.channels = channels;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        this.interpMode = interpMode;
    }

    /** Create a property seeded from the target's current value (one keyframe per channel at that value). */
    public static AnimatedProperty create(AnimatedPropertyType type, FXObject target) {
        var base = type.capture(target);
        var channels = new ECBCurves[base.length];
        for (int i = 0; i < base.length; i++) {
            channels[i] = single(base[i]);
        }
        var range = type.defaultRange(base);
        return new AnimatedProperty(type, base.clone(), channels, range[0], range[1], INTERP_DEFAULT);
    }

    /** A single keyframe at (0, v), stored as a zero-width segment so it reads as exactly one key. */
    private static ECBCurves single(float v) {
        var curves = new ECBCurves();
        curves.getSegments().clear();
        curves.getSegments().add(new ExplicitCubicBezierCurve2(
                new Vector2f(0, v), new Vector2f(0, v), new Vector2f(0, v), new Vector2f(0, v)));
        return curves;
    }

    public AnimatedPropertyType type() {
        return type;
    }

    public int channelCount() {
        return channels.length;
    }

    public float[] base() {
        return base.clone();
    }

    public ECBCurves channel(int axis) {
        return channels[axis];
    }

    public float rangeMin() {
        return rangeMin;
    }

    public float rangeMax() {
        return rangeMax;
    }

    public void setRange(float min, float max) {
        this.rangeMin = min;
        this.rangeMax = max;
    }

    public int interpMode() {
        return interpMode;
    }

    public void interpMode(int interpMode) {
        this.interpMode = interpMode;
    }

    /** Sample one channel at {@code x} (ticks), clamping to the first/last keyframe value outside the
     *  curve. Robust to zero-width (single keyframe) segments (no division by zero). */
    public static float sampleChannel(ECBCurves curve, float x) {
        var segments = curve.getSegments();
        if (segments.isEmpty()) return 0;
        var first = segments.getFirst();
        var last = segments.getLast();
        if (x <= first.p0.x) return first.p0.y;
        if (x >= last.p1.x) return last.p1.y;
        for (var segment : segments) {
            if (segment.p1.x > segment.p0.x && x >= segment.p0.x && x <= segment.p1.x) {
                return segment.getPoint((x - segment.p0.x) / (segment.p1.x - segment.p0.x)).y;
            }
        }
        return last.p1.y;
    }

    /** Sample all channels at {@code time} (ticks), via the property type (handles rotation modes). */
    public float[] sample(double time) {
        return type.sample(this, (float) time);
    }

    /** Drive {@code target} from the sampled value at {@code time}. */
    public void apply(FXObject target, double time) {
        type.apply(target, sample(time));
    }

    /** Restore the captured authored value (used when the property/track is removed or muted). */
    public void restoreBase(FXObject target) {
        type.apply(target, base());
    }

    /** Distinct keyframe times (segment endpoints) across all channels, for the collapsed-lane dots. */
    public List<Double> keyframeTimes() {
        var times = new TreeSet<Double>();
        for (var channel : channels) {
            for (var segment : channel.getSegments()) {
                times.add((double) segment.p0.x);
                times.add((double) segment.p1.x);
            }
        }
        return new ArrayList<>(times);
    }

    // ------------------------------------------------------------------ keyframe editing

    private static boolean isSingleKey(ECBCurves curve) {
        var segs = curve.getSegments();
        return segs.size() == 1 && segs.getFirst().p0.equals(segs.getFirst().p1);
    }

    /** A cubic segment a→b with control points at 1/3 and 2/3 along the line (smooth, no step case). */
    private static ExplicitCubicBezierCurve2 makeSegment(Vector2f a, Vector2f b) {
        return new ExplicitCubicBezierCurve2(new Vector2f(a),
                new Vector2f(a).lerp(b, 1 / 3f), new Vector2f(a).lerp(b, 2 / 3f), new Vector2f(b));
    }

    public int keyCount(int axis) {
        var segs = channels[axis].getSegments();
        if (isSingleKey(channels[axis])) return 1;
        return segs.size() + 1;
    }

    /** The (tick, value) of keyframe {@code k}. */
    public Vector2f key(int axis, int k) {
        var segs = channels[axis].getSegments();
        if (k <= 0) return new Vector2f(segs.getFirst().p0);
        if (k >= segs.size()) return new Vector2f(segs.getLast().p1);
        return new Vector2f(segs.get(k).p0);
    }

    /** Incoming tangent handle of key {@code k} (null for the first key / single key). */
    @Nullable
    public Vector2f inHandle(int axis, int k) {
        if (k <= 0 || isSingleKey(channels[axis])) return null;
        return new Vector2f(channels[axis].getSegments().get(k - 1).c1);
    }

    /** Outgoing tangent handle of key {@code k} (null for the last key / single key). */
    @Nullable
    public Vector2f outHandle(int axis, int k) {
        var segs = channels[axis].getSegments();
        if (isSingleKey(channels[axis]) || k >= segs.size()) return null;
        return new Vector2f(segs.get(k).c0);
    }

    public void moveKey(int axis, int k, float tick, float value) {
        var segs = channels[axis].getSegments();
        var result = new Vector2f(tick, value);
        if (isSingleKey(channels[axis])) {
            var s = segs.getFirst();
            s.p0.set(result); s.c0.set(result); s.c1.set(result); s.p1.set(result);
            return;
        }
        if (k < segs.size()) {
            var off = new Vector2f(result).sub(segs.get(k).p0);
            segs.get(k).p0.set(result);
            segs.get(k).c0.add(off);
        }
        if (k > 0) {
            var off = new Vector2f(result).sub(segs.get(k - 1).p1);
            segs.get(k - 1).p1.set(result);
            segs.get(k - 1).c1.add(off);
        }
    }

    public void setInHandle(int axis, int k, float tick, float value) {
        if (k > 0 && !isSingleKey(channels[axis])) {
            channels[axis].getSegments().get(k - 1).c1.set(tick, value);
        }
    }

    public void setOutHandle(int axis, int k, float tick, float value) {
        var segs = channels[axis].getSegments();
        if (!isSingleKey(channels[axis]) && k < segs.size()) {
            segs.get(k).c0.set(tick, value);
        }
    }

    /** Insert a keyframe at (tick, value); returns its new index (or -1 if at an existing key time). */
    public int addKey(int axis, float tick, float value) {
        var segs = channels[axis].getSegments();
        var p = new Vector2f(tick, value);
        if (isSingleKey(channels[axis])) {
            var existing = new Vector2f(segs.getFirst().p0);
            if (tick == existing.x) return -1;
            segs.clear();
            if (tick > existing.x) { segs.add(makeSegment(existing, p)); return 1; }
            segs.add(makeSegment(p, existing));
            return 0;
        }
        if (tick <= segs.getFirst().p0.x) {
            if (tick == segs.getFirst().p0.x) return -1;
            segs.addFirst(makeSegment(p, new Vector2f(segs.getFirst().p0)));
            return 0;
        }
        if (tick >= segs.getLast().p1.x) {
            if (tick == segs.getLast().p1.x) return -1;
            segs.add(makeSegment(new Vector2f(segs.getLast().p1), p));
            return segs.size();
        }
        for (int i = 0; i < segs.size(); i++) {
            var seg = segs.get(i);
            if (tick > seg.p0.x && tick < seg.p1.x) {
                var oldEnd = new Vector2f(seg.p1);
                var oldC1 = new Vector2f(seg.c1);
                seg.p1.set(p);
                seg.c1.set(new Vector2f(seg.p0).lerp(p, 2 / 3f));
                var next = makeSegment(p, oldEnd);
                next.c1.set(oldC1);
                segs.add(i + 1, next);
                return i + 1;
            }
        }
        return -1;
    }

    public void removeKey(int axis, int k) {
        var segs = channels[axis].getSegments();
        if (isSingleKey(channels[axis])) return; // never remove the last remaining key
        if (segs.size() == 1) { // two keys → keep the other as a single (zero-width) key
            var seg = segs.getFirst();
            var keep = new Vector2f(k == 0 ? seg.p1 : seg.p0);
            segs.clear();
            segs.add(new ExplicitCubicBezierCurve2(new Vector2f(keep), new Vector2f(keep), new Vector2f(keep), new Vector2f(keep)));
            return;
        }
        if (k == 0) {
            segs.removeFirst();
        } else if (k < segs.size()) {
            segs.get(k - 1).p1.set(segs.get(k).p1);
            segs.get(k - 1).c1.set(segs.get(k).c0);
            segs.remove(k);
        } else {
            segs.removeLast();
        }
    }

    /** Deep copy of all channels, for undo snapshots. */
    public ECBCurves[] snapshotChannels() {
        var copy = new ECBCurves[channels.length];
        for (int i = 0; i < channels.length; i++) {
            copy[i] = channels[i].copy();
        }
        return copy;
    }

    /** Restore channels from a {@link #snapshotChannels()} snapshot (keeps this property's identity). */
    public void restoreChannels(ECBCurves[] snapshot) {
        for (int i = 0; i < channels.length; i++) {
            channels[i].getSegments().clear();
            channels[i].getSegments().addAll(snapshot[i].copy().getSegments());
        }
    }

    public AnimatedProperty copy() {
        var copy = new ECBCurves[channels.length];
        for (int i = 0; i < channels.length; i++) {
            copy[i] = channels[i].copy();
        }
        return new AnimatedProperty(type, base.clone(), copy, rangeMin, rangeMax, interpMode);
    }

    // ------------------------------------------------------------------ serialization

    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        tag.putString("type", type.name());
        tag.put("base", floats(base));
        var chs = new ListTag();
        for (var channel : channels) {
            chs.add(channel.serializeNBT(provider));
        }
        tag.put("channels", chs);
        tag.putFloat("rangeMin", rangeMin);
        tag.putFloat("rangeMax", rangeMax);
        tag.putInt("interp", interpMode);
        return tag;
    }

    /** Deserialize a property; returns {@code null} if its type is no longer registered. */
    @Nullable
    public static AnimatedProperty deserialize(HolderLookup.Provider provider, CompoundTag tag) {
        var typeName = tag.getString("type");
        var type = PhotonRegistries.ANIMATED_PROPERTIES.get(typeName);
        if (type == null) {
            Photon.LOGGER.warn("Unknown animated property type '{}' skipped while loading", typeName);
            return null;
        }
        var base = readFloats(tag.getList("base", Tag.TAG_FLOAT));
        var channels = new ECBCurves[type.channelCount()];
        var chs = tag.getList("channels", Tag.TAG_LIST);
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new ECBCurves();
            if (i < chs.size()) {
                channels[i].deserializeNBT(provider, chs.getList(i));
            }
        }
        // tolerate a base shorter/longer than the current channel count (type changed)
        var fixedBase = new float[type.channelCount()];
        System.arraycopy(base, 0, fixedBase, 0, Math.min(base.length, fixedBase.length));
        return new AnimatedProperty(type, fixedBase, channels,
                tag.getFloat("rangeMin"), tag.getFloat("rangeMax"), tag.getInt("interp"));
    }

    private static ListTag floats(float[] values) {
        var list = new ListTag();
        for (var v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }

    private static float[] readFloats(ListTag list) {
        var values = new float[list.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = list.getFloat(i);
        }
        return values;
    }
}
