package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.lowdraglib2.math.curve.ExplicitCubicBezierCurve2;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.ECBCurves;
import expr.Variable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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
    /** Expression variables (interned, shared across all properties — eval is synchronous so this is safe). */
    private static final Variable T = Variable.make("t");
    private static final Variable PI = Variable.make("PI");
    static {
        PI.setValue(Math.PI);
    }

    protected final AnimatedPropertyType type;
    private final float[] base;          // captured authored value, one per channel
    private final ECBCurves[] channels;  // one keyframe curve per channel
    /** Per-channel expression clips: while the playhead is inside a clip, its {@code y=f(t)} value
     *  (t clip-local) overrides the curve; a parse/eval failure falls back to the curve. */
    private final List<ExprClip>[] exprClips;
    private float rangeMin;              // shared display value range (one Y axis for all channels)
    private float rangeMax;

    @SuppressWarnings("unchecked")
    public AnimatedProperty(AnimatedPropertyType type, float[] base, ECBCurves[] channels,
                            float rangeMin, float rangeMax) {
        this.type = type;
        this.base = base;
        this.channels = channels;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        var n = channels.length;
        this.exprClips = (List<ExprClip>[]) new List[n];
        for (int i = 0; i < n; i++) this.exprClips[i] = new ArrayList<>();
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

    // ------------------------------------------------------------------ per-channel expression clips

    /** The (mutable) list of expression clips on {@code axis} (may be empty). */
    public List<ExprClip> exprClips(int axis) {
        return exprClips[axis];
    }

    public void addExprClip(int axis, ExprClip clip) {
        exprClips[axis].add(clip);
    }

    public void removeExprClip(int axis, ExprClip clip) {
        exprClips[axis].remove(clip);
    }

    /** The expression clip active at {@code time} on {@code axis} (earliest wins on overlap), or null. */
    @Nullable
    public ExprClip activeExprClip(int axis, float time) {
        for (var clip : exprClips[axis]) {
            if (clip.contains(time)) return clip;
        }
        return null;
    }

    /** The expression override for {@code axis} at {@code time}: the value of the active clip's {@code
     *  y = f(t)} (t clip-local) when it compiles and evaluates finite, else {@code null} so the caller
     *  falls back to the keyframe curve. */
    @Nullable
    public Float evalExpr(int axis, float time) {
        var clip = activeExprClip(axis, time);
        if (clip == null) return null;
        var expr = clip.compiled();
        if (expr == null) return null;
        T.setValue(time - clip.start());
        var v = expr.value();
        return Double.isFinite(v) ? (float) v : null;
    }

    /** Sample one channel at {@code time} (ticks): the active expression clip's value if any, otherwise
     *  the keyframe curve. */
    public float sampleChannelValue(int axis, float time) {
        var e = evalExpr(axis, time);
        return e != null ? e : sampleChannel(channels[axis], time);
    }

    /** Step (hold) sample of one channel at {@code time} (ticks): the value of the latest keyframe whose
     *  tick is {@code <= time} (the first keyframe's value before it), with the active expression clip
     *  taking priority. Used by discrete (int / boolean) config channels. */
    public float sampleChannelStepped(int axis, float time) {
        var e = evalExpr(axis, time);
        if (e != null) return e;
        var count = keyCount(axis);
        var value = key(axis, 0).y;
        for (int k = 0; k < count; k++) {
            var kf = key(axis, k);
            if (kf.x <= time) {
                value = kf.y;
            } else {
                break;
            }
        }
        return value;
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
        type.restore(target, this);
    }

    /** Distinct keyframe times (segment endpoints) across all channels, for the collapsed-lane dots. */
    public List<Double> keyframeTimes() {
        var times = new TreeSet<Double>();
        for (int axis = 0; axis < channels.length; axis++) {
            for (var segment : channels[axis].getSegments()) {
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

    /** Restore channels + range + per-channel expression clips (+ subclass extras) from another
     *  property (undo). */
    public void restoreFrom(AnimatedProperty other) {
        restoreChannels(other.snapshotChannels());
        setRange(other.rangeMin, other.rangeMax);
        restoreExprClipsFrom(other);
        restoreExtraFrom(other);
    }

    /** Copy per-channel expression clips (deep) from {@code other} into this property. */
    protected void restoreExprClipsFrom(AnimatedProperty other) {
        for (int i = 0; i < exprClips.length && i < other.exprClips.length; i++) {
            exprClips[i].clear();
            for (var clip : other.exprClips[i]) exprClips[i].add(clip.copy());
        }
    }

    /** Deep copy of one channel's expression clips, for undo snapshots. */
    public List<ExprClip> snapshotExprClips(int axis) {
        var copy = new ArrayList<ExprClip>();
        for (var clip : exprClips[axis]) copy.add(clip.copy());
        return copy;
    }

    /** Restore one channel's expression clips from a {@link #snapshotExprClips(int)} snapshot. When the
     *  clip count matches, existing clips are mutated in place (references stay valid across a drag);
     *  otherwise the list is rebuilt. */
    public void restoreExprClips(int axis, List<ExprClip> snapshot) {
        var list = exprClips[axis];
        if (list.size() == snapshot.size()) {
            for (int i = 0; i < list.size(); i++) {
                var src = snapshot.get(i);
                list.get(i).start(src.start()).duration(src.duration()).expression(src.expression());
            }
        } else {
            list.clear();
            for (var clip : snapshot) list.add(clip.copy());
        }
    }

    /** Hook for subclasses to restore their extra state (e.g. rotation interp mode). */
    protected void restoreExtraFrom(AnimatedProperty other) {
    }

    public AnimatedProperty copy() {
        var copy = new AnimatedProperty(type, base.clone(), snapshotChannels(), rangeMin, rangeMax);
        copy.restoreExprClipsFrom(this);
        return copy;
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

    /** Write the per-channel expression clips as an {@code "exprClips"} list-of-lists (counterpart of
     *  {@link #readExprClips}). */
    public static void writeExprClips(CompoundTag tag, AnimatedProperty property) {
        var channels = new ListTag();
        for (int axis = 0; axis < property.channelCount(); axis++) {
            var clips = new ListTag();
            for (var clip : property.exprClips(axis)) {
                var clipTag = new CompoundTag();
                clipTag.put("start", DoubleTag.valueOf(clip.start()));
                clipTag.put("duration", DoubleTag.valueOf(clip.duration()));
                clipTag.put("expr", StringTag.valueOf(clip.expression()));
                clips.add(clipTag);
            }
            channels.add(clips);
        }
        tag.put("exprClips", channels);
    }

    /** Read the per-channel expression clips onto {@code property}. Tolerant of missing lists. Also
     *  migrates the legacy {@code "modes"}/{@code "exprs"} format (an {@code EXPRESSION} channel, ordinal
     *  1) into one full-span clip starting at tick 0 — with clip-local {@code t} and start 0 this
     *  reproduces the old absolute-{@code t} behavior. */
    public static void readExprClips(CompoundTag tag, AnimatedProperty property) {
        if (tag.contains("exprClips", Tag.TAG_LIST)) {
            var channels = tag.getList("exprClips", Tag.TAG_LIST);
            for (int axis = 0; axis < property.channelCount() && axis < channels.size(); axis++) {
                var clips = channels.getList(axis);
                for (int i = 0; i < clips.size(); i++) {
                    var clipTag = clips.getCompound(i);
                    property.addExprClip(axis, new ExprClip(
                            clipTag.getDouble("start"), clipTag.getDouble("duration"), clipTag.getString("expr")));
                }
            }
            return;
        }
        // legacy migration: EXPRESSION-mode channels become a single full-span clip
        var modes = tag.getList("modes", Tag.TAG_INT);
        var exprs = tag.getList("exprs", Tag.TAG_STRING);
        for (int axis = 0; axis < property.channelCount(); axis++) {
            if (axis < modes.size() && modes.getInt(axis) == 1) {
                var expr = axis < exprs.size() ? exprs.getString(axis) : "";
                property.addExprClip(axis, new ExprClip(0, 1e9, expr));
            }
        }
    }
}
