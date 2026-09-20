package com.lowdragmc.photon.client.gameobject.emitter.data.model.skin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Moving clips from an animation-only file's skeleton onto a character's, by joint name. */
class ClipRetargetTest {

    private static final float[] REST = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};

    /** A skeleton of roots with the given names, in the given order. */
    private static Skeleton of(String... names) {
        var builder = new Skeleton.Builder();
        for (int i = 0; i < names.length; i++) {
            builder.joint(i, -1, names[i], REST, 0);
        }
        var out = new Skeleton[1];
        builder.sortInto(out);
        return out[0];
    }

    private static AnimationClip clip(String name, int... joints) {
        var channels = new java.util.ArrayList<AnimationClip.Channel>();
        for (int joint : joints) {
            channels.add(new AnimationClip.Channel(joint, AnimationClip.Path.TRANSLATION,
                    AnimationClip.Interpolation.LINEAR, new float[]{0f, 1f}, new float[]{0, 0, 0, 0, 1, 0}));
        }
        return new AnimationClip(name, channels);
    }

    /** The point of the whole thing: the two files agree on names, not on joint order. */
    @Test
    void matchesByNameNotByIndex() {
        var target = of("hips", "spine", "head");
        var source = of("head", "hips", "spine");
        var result = ClipRetarget.onto(target, source, List.of(clip("x", 0, 2)), "idle");

        assertEquals(3, result.matchedJoints());
        assertEquals(0, result.droppedChannels());
        var channels = result.clips().getFirst().channels();
        assertEquals(2, channels.get(0).joint(), "source joint 0 is 'head', which is target joint 2");
        assertEquals(1, channels.get(1).joint(), "source joint 2 is 'spine', which is target joint 1");
    }

    @Test
    void channelsNamingAJointTheTargetLacksAreDropped() {
        var target = of("hips");
        var source = of("hips", "tail");
        var result = ClipRetarget.onto(target, source, List.of(clip("x", 0, 1)), "idle");

        assertEquals(1, result.matchedJoints());
        assertEquals(1, result.droppedChannels());
        assertEquals(1, result.clips().getFirst().channels().size());
    }

    /** A clip left with nothing to drive is not a clip — and it is the signal for "wrong rig". */
    @Test
    void aClipThatMatchesNothingDisappears() {
        var result = ClipRetarget.onto(of("hips"), of("Bip01_Pelvis"), List.of(clip("x", 0)), "idle");
        assertTrue(result.clips().isEmpty());
        assertEquals(0, result.matchedJoints());
        assertEquals(1, result.droppedChannels());
    }

    /**
     * ⚠️ Exporters write the same clip name into every file ("Armature|mixamo.com|Layer0"), so a
     * single-clip file is named after the FILE — otherwise twenty animations all arrive called the
     * same thing and only the first is reachable.
     */
    @Test
    void aSingleClipFileIsNamedAfterTheFile() {
        var skeleton = of("hips");
        var result = ClipRetarget.onto(skeleton, skeleton,
                List.of(clip("Armature|mixamo.com|Layer0", 0)), "idle");
        assertEquals(List.of("idle"), result.clips().stream().map(AnimationClip::name).toList());
    }

    @Test
    void aMultiClipFileKeepsItsClipNamesUnderTheFilesName() {
        var skeleton = of("hips");
        var result = ClipRetarget.onto(skeleton, skeleton,
                List.of(clip("Walk", 0), clip("Run", 0)), "locomotion");
        assertEquals(List.of("locomotion/Walk", "locomotion/Run"),
                result.clips().stream().map(AnimationClip::name).toList());
    }

    /** The keyframe data is carried over untouched; only the joint it points at changes. */
    @Test
    void keyframesSurviveTheMove() {
        var target = of("a", "hips");
        var source = of("hips");
        var original = clip("x", 0).channels().getFirst();
        var result = ClipRetarget.onto(target, source, List.of(clip("x", 0)), "idle");

        var moved = result.clips().getFirst().channels().getFirst();
        assertEquals(1, moved.joint());
        assertArrayEquals(original.times(), moved.times());
        assertArrayEquals(original.values(), moved.values());
        assertEquals(original.path(), moved.path());
        assertEquals(original.interpolation(), moved.interpolation());
        assertEquals(1f, result.clips().getFirst().duration(), 1e-6f);
    }
}
