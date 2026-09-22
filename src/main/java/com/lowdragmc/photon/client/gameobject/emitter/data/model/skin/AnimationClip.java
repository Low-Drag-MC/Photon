package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;


import java.util.List;

/**
 * One glTF animation: channels driving joint translation, rotation or scale over time.
 * {@link #sample} writes into a TRS array the caller pre-filled with the rest pose, because a clip only
 * says what it animates.
 */
public final class AnimationClip {

    public enum Path {
        TRANSLATION(3, 0),
        ROTATION(4, 3),
        SCALE(3, 7);

        final int components;
        /** Offset inside a joint's {@link Skeleton#FLOATS_PER_TRS} floats. */
        final int trsOffset;

        Path(int components, int trsOffset) {
            this.components = components;
            this.trsOffset = trsOffset;
        }
    }

    public enum Interpolation {
        LINEAR,
        STEP,
        /** Values come as {@code (inTangent, value, outTangent)} triples. */
        CUBICSPLINE
    }

    /** {@code values} is {@code times.length * path.components} floats, or three times that for CUBICSPLINE. */
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

    public float duration() {
        return duration;
    }

    public List<Channel> channels() {
        return channels;
    }

    /**
     * Overwrite {@code trs} with this clip's pose at {@code time} (seconds), leaving every joint and
     * component no channel touches alone.
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

        // glTF clamps outside the clip; looping is the caller's business
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
        int stride = channel.interpolation == Interpolation.CUBICSPLINE ? components * 3 : components;
        int off = key * stride + (channel.interpolation == Interpolation.CUBICSPLINE ? components : 0);
        for (int c = 0; c < components; c++) {
            out[outOff + c] = channel.values[off + c];
        }
    }

    /**
     * The spec's cubic Hermite. ⚠️ The tangents are scaled by the keyframe interval; dropping that makes
     * the animation overshoot in proportion to its frame spacing.
     */
    private static void cubicSpline(Channel channel, int prev, int next, float t, float span,
                                    float[] out, int outOff, int components) {
        int stride = components * 3;
        var v = channel.values;
        int p0 = prev * stride + components;
        int b0 = prev * stride + components * 2;
        int a1 = next * stride;
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

    /** Slerp along the short arc, as glTF specifies for a rotation channel. */
    private static void slerp(float[] values, int a, int b, float t, float[] out, int outOff) {
        float ax = values[a], ay = values[a + 1], az = values[a + 2], aw = values[a + 3];
        float bx = values[b], by = values[b + 1], bz = values[b + 2], bw = values[b + 3];
        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0f) { // q and -q are the same rotation
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            dot = -dot;
        }
        float s0, s1;
        if (dot > 0.9995f) { // nearly parallel: slerp degenerates, lerp+normalize is indistinguishable
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
     * ⚠️ After sampling, not before composing: a cubic-spline channel does not produce unit quaternions
     * even from unit keyframes, and {@link Skeleton#fromTrs} assumes unit.
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
