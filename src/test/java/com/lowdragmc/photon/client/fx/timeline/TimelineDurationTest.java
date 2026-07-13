package com.lowdragmc.photon.client.fx.timeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MC-free unit tests for {@link Timeline#getDuration()} / {@link Track#contentEnd()}. Tracks are
 * built directly (no {@code TrackType} registry needed — contentEnd never touches the type).
 */
class TimelineDurationTest {

    private static AnimatedProperty propertyWithKeys(float... ticks) {
        var p = new AnimatedProperty(null, new float[]{0}, AnimatedProperty.seedChannels(new float[]{0}), -1, 1);
        for (var tick : ticks) {
            p.putKey(0, tick, 1);
        }
        return p;
    }

    @Test
    void emptyTimelineHasZeroDuration() {
        assertEquals(0, new Timeline().getDuration(), 1e-9);
    }

    @Test
    void clipTracksUseTheLatestClipEnd() {
        var timeline = new Timeline();
        var activator = new ActivatorTrack();
        activator.clips().add(new Clip(0, 20, 1));
        activator.clips().add(new Clip(40, 20, 1)); // ends at 60
        var control = new ControlTrack();
        control.clips().add(new Clip(10, 25, 1)); // ends at 35
        timeline.tracks().add(activator);
        timeline.tracks().add(control);
        assertEquals(60, timeline.getDuration(), 1e-9);
    }

    @Test
    void mutedLeafContributesNothing() {
        var timeline = new Timeline();
        var track = new ActivatorTrack();
        track.clips().add(new Clip(0, 100, 1));
        track.mute(true);
        timeline.tracks().add(track);
        assertEquals(0, timeline.getDuration(), 1e-9);
    }

    @Test
    void mutedGroupSubtreeContributesNothing() {
        var timeline = new Timeline();
        var group = new TrackGroup();
        group.mute(true);
        var child = new ActivatorTrack();
        child.clips().add(new Clip(0, 100, 1));
        group.children().add(child);
        timeline.tracks().add(group);
        var direct = new ControlTrack();
        direct.clips().add(new Clip(0, 30, 1));
        timeline.tracks().add(direct);
        assertEquals(30, timeline.getDuration(), 1e-9);
    }

    @Test
    void nestedGroupChildrenAreCounted() {
        var timeline = new Timeline();
        var group = new TrackGroup();
        var child = new ActivatorTrack();
        child.clips().add(new Clip(50, 25, 1)); // ends at 75
        group.children().add(child);
        timeline.tracks().add(group);
        assertEquals(75, timeline.getDuration(), 1e-9);
    }

    @Test
    void signalTrackLastsUntilItsLastSignal() {
        var track = new SignalTrack();
        track.signals().add(new Signal(120, "boom"));
        track.signals().add(new Signal(30, "warmup"));
        assertEquals(120, track.contentEnd(), 1e-9);
    }

    @Test
    void animationTrackLastsUntilItsLastKeyframe() {
        var track = new AnimationTrack();
        track.properties().add(propertyWithKeys(0, 15, 80));
        track.properties().add(propertyWithKeys(0, 40));
        assertEquals(80, track.contentEnd(), 1e-9);
    }

    @Test
    void expressionClipsDoNotExtendTheDuration() {
        var track = new AnimationTrack();
        var p = propertyWithKeys(0, 10);
        // the legacy expr-clip migration produces effectively-infinite clips — they must not count
        p.addExprClip(0, new ExprClip(0, 1_000_000_000d, "t"));
        track.properties().add(p);
        assertEquals(10, track.contentEnd(), 1e-9);
    }

    @Test
    void durationIsTheGlobalMaxAcrossTrackKinds() {
        var timeline = new Timeline();
        var activator = new ActivatorTrack();
        activator.clips().add(new Clip(0, 20, 1));
        var signals = new SignalTrack();
        signals.signals().add(new Signal(200, "late"));
        var animation = new AnimationTrack();
        animation.properties().add(propertyWithKeys(0, 90));
        timeline.tracks().add(activator);
        timeline.tracks().add(signals);
        timeline.tracks().add(animation);
        assertEquals(200, timeline.getDuration(), 1e-9);
    }
}
