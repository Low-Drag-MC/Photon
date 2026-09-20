package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * One animation: a set of channels, each driving one joint's translation, rotation or scale over time.
 * glTF's {@code animation}, with its samplers' keyframes kept as they were authored.
 *
 * <p>{@link #sample} writes into a TRS array the caller has already filled with the skeleton's rest pose,
 * because <b>a clip only says what it animates</b>. A walk cycle that touches nothing but the legs leaves
 * the arms at rest, and a channel-driven-only array would leave them at the origin.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AnimationClip {

    public enum Path {
        TRANSLATION(3, 0),
        ROTATION(4, 3),
        SCALE(3, 7);

        /** Components per keyframe value. */
        final int components;
        /** Offset of this path inside a joint's {@link Skeleton#FLOATS_PER_TRS} floats. */
        final int trsOffset;

        Path(int components, int trsOffset) {
            this.components = components;
            this.trsOffset = trsOffset;
        }
    }

    public enum Interpolation {
        LINEAR,
        STEP,
        /** Keyframe values come as {@code (inTangent, value, outTangent)} triples; the spec's Hermite basis. */
        CUBICSPLINE
    }

    /**
     * @param joint  index into the {@link Skeleton}
     * @param times  keyframe times in seconds, ascending
     * @param values {@code times.length * path.components} floats, or three times that for CUBICSPLINE
     */
    public record Channel(int joint, Path path, Interpolation interpolation, float[] times, float[] values) {
    }

    private final String name;
    private final float duration;
    private final List<Channel> channels;

    public AnimationClip(String name, List<Channel> channels) {
        this.name = name;
        this.channels = channels;
        float last = 0f;
        for (var channel : channels) {
            if (channel.times.length > 0) {
                last = Math.max(last, channel.times[channel.times.length - 1]);
            }
        }
        this.duration = last;
    }

    public String name() {
        return name;
    }

    /** Seconds from the first keyframe to the last; {@code 0} for a clip that does not move. */
    public float duration() {
        return duration;
    }

    public List<Channel> channels() {
        return channels;
    }

    /**
     * Overwrite {@code trs} with this clip's pose at {@code time} (seconds), leaving every joint and
     * every component no channel touches exactly as it was.
     *
     * @param trs {@link Skeleton#jointCount()} x {@link Skeleton#FLOATS_PER_TRS} floats, pre-filled with
     *            the rest pose
     */
    public void sample(float time, float[] trs) {
        for (var channel : channels) {
            int base = channel.joint * Skeleton.FLOATS_PER_TRS + channel.path.trsOffset;
            if (base < 0 || base + channel.path.components > trs.length) continue;
            sampleChannel(channel, time, trs, base);
        }
        normalizeRotations(trs);
    }

    private static void sampleChannel(Channel channel, float time, float[] out, int outOff) {
        var times = channel.times;
        int components = channel.path.components;
        int keys = times.length;
        if (keys == 0) return;

        // A clip shorter than the query, or a query before it starts, holds the end it ran into. glTF
        // says to clamp; looping is the caller's business because only it knows the clip is looping.
        if (keys == 1 || time <= times[0]) {
            copyValue(channel, 0, out, outOff, components);
            return;
        }
        if (time >= times[keys - 1]) {
            copyValue(channel, keys - 1, out, outOff, components);
            return;
        }

        int next = upperBound(times, time);
        int prev = next - 1;
        float span = times[next] - times[prev];
        float t = span <= 0f ? 0f : (time - times[prev]) / span;

        switch (channel.interpolation) {
            case STEP -> copyValue(channel, prev, out, outOff, components);
            case CUBICSPLINE -> cubicSpline(channel, prev, next, t, span, out, outOff, components);
            case LINEAR -> {
                if (components == 4) {
                    slerp(channel.values, prev * 4, next * 4, t, out, outOff);
                } else {
                    int a = prev * components, b = next * components;
                    for (int c = 0; c < components; c++) {
                        out[outOff + c] = channel.values[a + c] + (channel.values[b + c] - channel.values[a + c]) * t;
                    }
                }
            }
        }
    }

    /** Index of the first keyframe strictly after {@code time}; only called when one exists. */
    private static int upperBound(float[] times, float time) {
        int low = 0, high = times.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (times[mid] <= time) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private static void copyValue(Channel channel, int key, float[] out, int outOff, int components) {
        // CUBICSPLINE stores (inTangent, value, outTangent); the value is the middle third
        int stride = channel.interpolation == Interpolation.CUBICSPLINE ? components * 3 : components;
        int off = key * stride + (channel.interpolation == Interpolation.CUBICSPLINE ? components : 0);
        for (int c = 0; c < components; c++) {
            out[outOff + c] = channel.values[off + c];
        }
    }

    /**
     * The spec's cubic Hermite: {@code p(t) = (2t³-3t²+1)v0 + (t³-2t²+t)·span·b0 + (-2t³+3t²)v1 +
     * (t³-t²)·span·a1}, where {@code b0} is the previous key's out-tangent and {@code a1} the next key's
     * in-tangent. ⚠️ The tangents are scaled by the <b>interval</b>, which is the part that is easy to
     * drop and produces an animation that overshoots in proportion to its frame spacing.
     */
    private static void cubicSpline(Channel channel, int prev, int next, float t, float span,
                                    float[] out, int outOff, int components) {
        int stride = components * 3;
        var v = channel.values;
        int p0 = prev * stride + components;        // value
        int b0 = prev * stride + components * 2;    // out-tangent
        int a1 = next * stride;                     // in-tangent
        int p1 = next * stride + components;

        float t2 = t * t, t3 = t2 * t;
        float h00 = 2 * t3 - 3 * t2 + 1;
        float h10 = t3 - 2 * t2 + t;
        float h01 = -2 * t3 + 3 * t2;
        float h11 = t3 - t2;
        for (int c = 0; c < components; c++) {
            out[outOff + c] = h00 * v[p0 + c] + h10 * span * v[b0 + c]
                    + h01 * v[p1 + c] + h11 * span * v[a1 + c];
        }
    }

    /**
     * Spherical linear interpolation, the shorter way round. glTF specifies slerp for a rotation channel;
     * a component-wise lerp would make a joint's speed vary across the arc and would collapse a 180 degree
     * turn into a shortcut through the origin.
     */
    private static void slerp(float[] values, int a, int b, float t, float[] out, int outOff) {
        float ax = values[a], ay = values[a + 1], az = values[a + 2], aw = values[a + 3];
        float bx = values[b], by = values[b + 1], bz = values[b + 2], bw = values[b + 3];
        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0f) { // take the short arc: q and -q are the same rotation
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            dot = -dot;
        }
        float s0, s1;
        if (dot > 0.9995f) {
            // nearly parallel: slerp degenerates, and lerp+normalize is both stable and indistinguishable
            s0 = 1f - t;
            s1 = t;
        } else {
            float theta = (float) Math.acos(dot);
            float sin = (float) Math.sin(theta);
            s0 = (float) Math.sin((1 - t) * theta) / sin;
            s1 = (float) Math.sin(t * theta) / sin;
        }
        out[outOff] = s0 * ax + s1 * bx;
        out[outOff + 1] = s0 * ay + s1 * by;
        out[outOff + 2] = s0 * az + s1 * bz;
        out[outOff + 3] = s0 * aw + s1 * bw;
    }

    /**
     * ⚠️ Rotations are renormalized after sampling, not before composing. A cubic-spline channel does not
     * produce unit quaternions even from unit keyframes, and {@link Skeleton#fromTrs} assumes unit — the
     * symptom of skipping this is a limb that subtly grows and shrinks as it swings.
     */
    private static void normalizeRotations(float[] trs) {
        for (int off = Path.ROTATION.trsOffset; off + 3 < trs.length; off += Skeleton.FLOATS_PER_TRS) {
            float x = trs[off], y = trs[off + 1], z = trs[off + 2], w = trs[off + 3];
            float len2 = x * x + y * y + z * z + w * w;
            if (len2 <= 1.0e-12f || !Float.isFinite(len2)) {
                trs[off] = 0f;
                trs[off + 1] = 0f;
                trs[off + 2] = 0f;
                trs[off + 3] = 1f;
            } else if (Math.abs(len2 - 1f) > 1.0e-6f) {
                float inv = 1f / (float) Math.sqrt(len2);
                trs[off] = x * inv;
                trs[off + 1] = y * inv;
                trs[off + 2] = z * inv;
                trs[off + 3] = w * inv;
            }
        }
    }
}
