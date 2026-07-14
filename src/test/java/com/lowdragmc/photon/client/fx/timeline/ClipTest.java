package com.lowdragmc.photon.client.fx.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MC-free unit tests for the Timeline evaluation core (clip lookup, gaps/overlaps,
 * enter/exit transitions, speed clamping). These must not touch any net.minecraft class.
 */
class ClipTest {

    @Test
    void endIsStartPlusDuration() {
        var clip = new Clip(10.0, 5.0, 1.0f);
        assertEquals(15.0, clip.end());
    }

    @Test
    void containsIsHalfOpen() {
        var clip = new Clip(10.0, 5.0, 1.0f);
        assertFalse(clip.contains(9.999), "before start");
        assertTrue(clip.contains(10.0), "at start is inclusive");
        assertTrue(clip.contains(14.999), "just before end");
        assertFalse(clip.contains(15.0), "end is exclusive so adjacent clips don't overlap");
    }

    @Test
    void localTimeIsOffsetClampedToDuration() {
        var clip = new Clip(10.0, 5.0, 1.0f);
        assertEquals(0.0, clip.localTime(10.0));
        assertEquals(2.0, clip.localTime(12.0));
        assertEquals(0.0, clip.localTime(5.0), "before start clamps to 0");
        assertEquals(5.0, clip.localTime(99.0), "after end clamps to duration");
    }

    @Test
    void activeClipReturnsClipContainingTime() {
        var a = new Clip(0.0, 5.0, 1.0f);
        var b = new Clip(10.0, 5.0, 1.0f);
        var clips = List.of(a, b);
        assertSame(a, Clip.activeClip(clips, 2.0));
        assertSame(b, Clip.activeClip(clips, 12.0));
    }

    @Test
    void activeClipReturnsNullInGaps() {
        var clips = List.of(new Clip(0.0, 5.0, 1.0f), new Clip(10.0, 5.0, 1.0f));
        assertNull(Clip.activeClip(clips, 7.0), "gap between clips");
        assertNull(Clip.activeClip(clips, -1.0), "before first clip");
        assertNull(Clip.activeClip(clips, 20.0), "after last clip");
    }

    @Test
    void activeClipPicksFirstWhenOverlapping() {
        var first = new Clip(0.0, 10.0, 1.0f);
        var second = new Clip(5.0, 10.0, 1.0f);
        var clips = List.of(first, second);
        assertSame(first, Clip.activeClip(clips, 7.0), "earliest clip in list wins on overlap");
    }

    @Test
    void phase1aSpeedClampsToZeroOrOne() {
        assertEquals(0.0f, Clip.phase1aSpeed(0.0f));
        assertEquals(0.0f, Clip.phase1aSpeed(-2.0f), "negative freezes");
        assertEquals(1.0f, Clip.phase1aSpeed(0.5f), "any positive runs at normal speed in phase 1a");
        assertEquals(1.0f, Clip.phase1aSpeed(3.0f));
    }

    @Test
    void copyIsIndependentValueCopy() {
        var id = new java.util.UUID(7, 9);
        var clip = new Clip(1.0, 2.0, 0.5f).targetId(id).seed(1234L).randomSeed(true);
        var copy = clip.copy();
        assertNotSame(clip, copy);
        assertEquals(1.0, copy.start());
        assertEquals(2.0, copy.duration());
        assertEquals(0.5f, copy.speed());
        assertEquals(id, copy.targetId(), "control-clip target is copied");
        assertEquals(1234L, copy.seed());
        assertTrue(copy.randomSeed());
        copy.start(9.0);
        assertEquals(1.0, clip.start(), "mutating the copy must not affect the original");
    }

    @Test
    void insertTimeShiftsClipsAtOrAfterAndGrowsStraddling() {
        var before = new Clip(0.0, 5.0, 1.0f);      // fully before the insertion point
        var straddle = new Clip(8.0, 6.0, 1.0f);    // [8,14): the insertion point 10 falls inside
        var after = new Clip(20.0, 5.0, 1.0f);      // starts after the insertion point
        var clips = List.of(before, straddle, after);
        Clip.insertTime(clips, 10.0, 4.0);
        assertEquals(0.0, before.start(), "clip before the point is untouched");
        assertEquals(5.0, before.duration());
        assertEquals(8.0, straddle.start(), "straddling clip keeps its start");
        assertEquals(10.0, straddle.duration(), "straddling clip grows by delta (gap opens inside it)");
        assertEquals(24.0, after.start(), "clip at/after the point shifts later by delta");
        assertEquals(5.0, after.duration());
    }

    @Test
    void insertTimeAtClipStartShiftsIt() {
        var clip = new Clip(10.0, 5.0, 1.0f);
        Clip.insertTime(List.of(clip), 10.0, 3.0); // start == atTick counts as "at or after"
        assertEquals(13.0, clip.start());
        assertEquals(5.0, clip.duration());
    }

    @Test
    void insertTimeNegativeDeltaIsTheInverse() {
        var a = new Clip(0.0, 5.0, 1.0f);
        var b = new Clip(20.0, 5.0, 1.0f);
        var clips = List.of(a, b);
        Clip.insertTime(clips, 10.0, 4.0);
        Clip.insertTime(clips, 10.0, -4.0);
        assertEquals(0.0, a.start(), "forward then inverse returns to the original layout");
        assertEquals(20.0, b.start());
    }

    @Test
    void transitionBetweenClips() {
        var a = new Clip(0.0, 5.0, 1.0f);
        var b = new Clip(10.0, 5.0, 1.0f);
        assertEquals(Clip.Transition.NONE, Clip.Transition.between(null, null));
        assertEquals(Clip.Transition.ENTER, Clip.Transition.between(null, a));
        assertEquals(Clip.Transition.EXIT, Clip.Transition.between(a, null));
        assertEquals(Clip.Transition.NONE, Clip.Transition.between(a, a), "same clip stays");
        assertEquals(Clip.Transition.SWITCH, Clip.Transition.between(a, b), "different clip switches");
    }
}
