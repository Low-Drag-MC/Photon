package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Keyframe sampling: the three interpolations glTF defines, clamping, and what a clip leaves alone. */
class AnimationClipTest {

    /** A TRS array for {@code joints} joints, all at rest (identity rotation, unit scale). */
    private static float[] restPose(int joints) {
        var trs = new float[joints * Skeleton.FLOATS_PER_TRS];
        for (int joint = 0; joint < joints; joint++) {
            int at = joint * Skeleton.FLOATS_PER_TRS;
            trs[at + 6] = 1f; // quaternion w
            trs[at + 7] = 1f;
            trs[at + 8] = 1f;
            trs[at + 9] = 1f;
        }
        return trs;
    }

    private static AnimationClip translationClip(AnimationClip.Interpolation interpolation,
                                                 float[] times, float[] values) {
        return new AnimationClip("clip", List.of(new AnimationClip.Channel(
                0, AnimationClip.Path.TRANSLATION, interpolation, times, values)));
    }

    @Test
    void linearInterpolatesBetweenKeyframes() {
        var clip = translationClip(AnimationClip.Interpolation.LINEAR,
                new float[]{0f, 2f}, new float[]{0, 0, 0, 0, 0, 8});
        var trs = restPose(1);
        clip.sample(0.5f, trs);
        assertEquals(2f, trs[2], 1e-5f, "a quarter of the way along z");
    }

    @Test
    void stepHoldsThePreviousKeyframe() {
        var clip = translationClip(AnimationClip.Interpolation.STEP,
                new float[]{0f, 1f}, new float[]{1, 0, 0, 9, 0, 0});
        var trs = restPose(1);
        clip.sample(0.99f, trs);
        assertEquals(1f, trs[0], 1e-5f, "STEP does not interpolate at all");
        clip.sample(1f, trs);
        assertEquals(9f, trs[0], 1e-5f, "and snaps exactly at the key");
    }

    @Test
    void samplingOutsideTheClipClampsToTheEnds() {
        var clip = translationClip(AnimationClip.Interpolation.LINEAR,
                new float[]{1f, 2f}, new float[]{3, 0, 0, 7, 0, 0});
        var trs = restPose(1);
        clip.sample(-5f, trs);
        assertEquals(3f, trs[0], 1e-5f, "before the first key");
        clip.sample(99f, trs);
        assertEquals(7f, trs[0], 1e-5f, "after the last key");
        assertEquals(2f, clip.duration(), 1e-5f);
    }

    /** A clip only says what it animates; everything else has to survive untouched. */
    @Test
    void aClipLeavesWhatItDoesNotAnimateAlone() {
        var clip = new AnimationClip("legs", List.of(new AnimationClip.Channel(
                1, AnimationClip.Path.TRANSLATION, AnimationClip.Interpolation.LINEAR,
                new float[]{0f}, new float[]{0, 5, 0})));
        var trs = restPose(3);
        // joint 0 and joint 2 are posed by whoever filled the rest pose in
        trs[0] = 1f;
        trs[2 * Skeleton.FLOATS_PER_TRS] = 2f;

        clip.sample(0f, trs);

        assertEquals(1f, trs[0], 1e-5f, "joint 0 is not in the clip");
        assertEquals(5f, trs[Skeleton.FLOATS_PER_TRS + 1], 1e-5f, "joint 1 is");
        assertEquals(2f, trs[2 * Skeleton.FLOATS_PER_TRS], 1e-5f, "joint 2 is not");
        // and the scale nobody animated is still 1, not 0
        assertEquals(1f, trs[7], 1e-5f);
        assertEquals(1f, trs[Skeleton.FLOATS_PER_TRS + 7], 1e-5f);
    }

    /**
     * ⚠️ Rotation channels slerp, and take the <b>short</b> arc. A component-wise lerp would vary the
     * joint's angular speed across the arc; taking the long way round would make a limb swing backwards
     * through a half turn to reach a pose a few degrees away.
     */
    @Test
    void rotationTakesTheShortArcAndStaysUnit() {
        // 0 degrees to 180 degrees about +Y, but the second keyframe is written as the NEGATED quaternion
        // — the same rotation, the opposite hemisphere, which is exactly when the sign fix has to fire
        float s = (float) Math.sin(Math.toRadians(45));
        float c = (float) Math.cos(Math.toRadians(45));
        var clip = new AnimationClip("turn", List.of(new AnimationClip.Channel(
                0, AnimationClip.Path.ROTATION, AnimationClip.Interpolation.LINEAR,
                new float[]{0f, 1f},
                new float[]{0, 0, 0, 1, -0, -s, -0, -c})));
        var trs = restPose(1);
        clip.sample(0.5f, trs);

        float x = trs[3], y = trs[4], z = trs[5], w = trs[6];
        assertEquals(1f, x * x + y * y + z * z + w * w, 1e-4f, "not unit — fromTrs assumes it is");
        // The negated keyframe is a +90 degree turn about +Y, so halfway is +45 degrees: the quaternion
        // (0, sin 22.5, 0, cos 22.5), in the SAME hemisphere as the identity it started from — that is what
        // flipping the sign before interpolating buys. Without the flip this slerps the long way round and
        // arrives at -135 degrees, y = -0.924, which is what the number below is really guarding.
        assertEquals(Math.sin(Math.toRadians(22.5)), y, 1e-3f,
                "took the long way round instead of the short arc");
        assertEquals(Math.cos(Math.toRadians(22.5)), w, 1e-3f);
    }

    /**
     * The spec's cubic Hermite, with tangents scaled by the keyframe interval. Dropping that scale
     * produces an animation whose overshoot grows with the frame spacing — plausible-looking, and wrong.
     */
    @Test
    void cubicSplineMatchesTheSpecsHermiteBasis() {
        // one component is enough to pin the basis; values are (inTangent, value, outTangent) per key
        float[] times = {0f, 2f};
        float[] values = {
                0, 0, 0, /* v0 */ 0, 0, 0, /* out0 */ 1, 0, 0,
                /* in1 */ 0, 0, 0, /* v1 */ 4, 0, 0, /* out1 */ 0, 0, 0,
        };
        var clip = translationClip(AnimationClip.Interpolation.CUBICSPLINE, times, values);
        var trs = restPose(1);
        clip.sample(1f, trs); // t = 0.5 of a 2 second span

        // h00*0 + h10*span*1 + h01*4 + h11*span*0, with t=0.5: h10 = 0.125-0.5+0.5 = 0.125, h01 = 0.5
        float expected = 0.125f * 2f * 1f + 0.5f * 4f;
        assertEquals(expected, trs[0], 1e-5f, "Hermite basis or tangent scaling is off");
    }

    @Test
    void aSingleKeyframeChannelIsAConstant() {
        var clip = translationClip(AnimationClip.Interpolation.LINEAR,
                new float[]{5f}, new float[]{1, 2, 3});
        var trs = restPose(1);
        clip.sample(0f, trs);
        assertEquals(1f, trs[0], 1e-5f);
        clip.sample(1000f, trs);
        assertEquals(3f, trs[2], 1e-5f);
        assertEquals(5f, clip.duration(), 1e-5f);
    }

    /** Scale channels write into the scale slot, not the translation one — an easy offset to get wrong. */
    @Test
    void eachPathWritesItsOwnSlot() {
        var clip = new AnimationClip("all", List.of(
                new AnimationClip.Channel(0, AnimationClip.Path.TRANSLATION,
                        AnimationClip.Interpolation.STEP, new float[]{0f}, new float[]{1, 2, 3}),
                new AnimationClip.Channel(0, AnimationClip.Path.ROTATION,
                        AnimationClip.Interpolation.STEP, new float[]{0f}, new float[]{0, 0, 0, 1}),
                new AnimationClip.Channel(0, AnimationClip.Path.SCALE,
                        AnimationClip.Interpolation.STEP, new float[]{0f}, new float[]{4, 5, 6})));
        var trs = restPose(1);
        clip.sample(0f, trs);
        assertArrayEquals(new float[]{1, 2, 3, 0, 0, 0, 1, 4, 5, 6}, trs, 1e-6f);
    }
}
