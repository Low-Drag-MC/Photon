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
 * <p>
 * Creation, serialization and inspection are owned by the {@link AnimatedPropertyType}; per-type extra
 * state (e.g. rotation's interpolation mode) lives on a subclass (see
 * {@code com.lowdragmc.photon.client.fx.timeline.property.RotationAnimatedProperty}).
 */
public class AnimatedProperty {
    protected final AnimatedPropertyType type;
    private final float[] base;          // captured authored value, one per channel
    private final ECBCurves[] channels;  // one keyframe curve per channel
    private float rangeMin;              // shared display value range (one Y axis for all channels)
    private float rangeMax;

    public AnimatedProperty(AnimatedPropertyType type, float[] base, ECBCurves[] channels,
                            float rangeMin, float rangeMax) {
        this.type = type;
        this.base = base;
        this.channels = channels;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
    }

    /** Seed one zero-width (single-keyframe) channel per base value. */
    public static ECBCurves[] seedChannels(float[] base) {
        var channels = new ECBCurves[base.length];
        for (int i = 0; i < base.length; i++) {
            channels[i] = single(base[i]);
        }
        return channels;
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

    /** Set the keyframe at {@code tick} to {@code value}: moves the existing key there (if any, matched
     *  to the rounded tick) or inserts a new one. Used by record mode (one key per channel per tick). */
    public int putKey(int axis, float tick, float value) {
        var count = keyCount(axis);
        for (int k = 0; k < count; k++) {
            if (Math.round(key(axis, k).x) == Math.round(tick)) {
                moveKey(axis, k, tick, value);
                return k;
            }
        }
        return addKey(axis, tick, value);
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

    /** Restore channels + range (+ subclass extras) from another property (for inspector-edit undo). */
    public void restoreFrom(AnimatedProperty other) {
        restoreChannels(other.snapshotChannels());
        setRange(other.rangeMin, other.rangeMax);
        restoreExtraFrom(other);
    }

    /** Hook for subclasses to restore their extra state (e.g. rotation interp mode). */
    protected void restoreExtraFrom(AnimatedProperty other) {
    }

    public AnimatedProperty copy() {
        return new AnimatedProperty(type, base.clone(), snapshotChannels(), rangeMin, rangeMax);
    }

    /** Build a configurator for this property's inspector (e.g. rotation interp mode), or {@code null}. */
    @Nullable
    public com.lowdragmc.lowdraglib2.configurator.IConfigurable inspect(Runnable onChanged) {
        return type.inspect(this, onChanged);
    }

    // ------------------------------------------------------------------ serialization

    /** Serialize this property via its {@link AnimatedPropertyType} (type owns the format). */
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        return type.serialize(provider, this);
    }

    /** Dispatcher: read the property type from {@code tag} and delegate to {@link AnimatedPropertyType#deserialize}.
     *  Returns {@code null} if the type is no longer registered. */
    @Nullable
    public static AnimatedProperty deserialize(HolderLookup.Provider provider, CompoundTag tag) {
        var typeName = tag.getString("type");
        var type = PhotonRegistries.ANIMATED_PROPERTIES.get(typeName);
        if (type == null) {
            Photon.LOGGER.warn("Unknown animated property type '{}' skipped while loading", typeName);
            return null;
        }
        return type.deserialize(provider, tag);
    }

    // ---- shared (de)serialization helpers used by AnimatedPropertyType defaults ----

    public static ListTag floatsToTag(float[] values) {
        var list = new ListTag();
        for (var v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }

    public static float[] readFloatsFromTag(ListTag list) {
        var values = new float[list.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = list.getFloat(i);
        }
        return values;
    }

    /** Read {@code count} channels from the {@code "channels"} list of {@code tag}. */
    public static ECBCurves[] readChannels(HolderLookup.Provider provider, CompoundTag tag, int count) {
        var channels = new ECBCurves[count];
        var chs = tag.getList("channels", Tag.TAG_LIST);
        for (int i = 0; i < count; i++) {
            channels[i] = new ECBCurves();
            if (i < chs.size()) {
                channels[i].deserializeNBT(provider, chs.getList(i));
            }
        }
        return channels;
    }
}
