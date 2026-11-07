package com.lowdragmc.photon.client.fx.timeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A control clip's exit and seam modes round-trip, and the defaults write nothing new so older files read and save as before. */
class ClipModesNbtTest {

    private static ControlTrack track(Clip.ExitMode mode) {
        var track = new ControlTrack();
        track.clips().add(new Clip(5, 20, 1).exitMode(mode));
        return track;
    }

    @Test
    void drainRoundTrips() {
        var tag = track(Clip.ExitMode.DRAIN).writeData(null);
        var read = new ControlTrack();
        read.readData(null, tag);
        assertEquals(Clip.ExitMode.DRAIN, read.clips().getFirst().exitMode());
    }

    @Test
    void cutWritesNoKey() {
        var tag = track(Clip.ExitMode.CUT).writeData(null);
        var clipTag = tag.getListOrEmpty("clips").getCompoundOrEmpty(0);
        assertFalse(clipTag.contains("exit"), "a cut clip saves exactly as before");
        var read = new ControlTrack();
        read.readData(null, tag);
        assertEquals(Clip.ExitMode.CUT, read.clips().getFirst().exitMode());
    }

    @Test
    void aFileWithoutTheKeyReadsAsCut() {
        var tag = track(Clip.ExitMode.DRAIN).writeData(null);
        tag.getListOrEmpty("clips").getCompoundOrEmpty(0).remove("exit");
        var read = new ControlTrack();
        read.readData(null, tag);
        assertEquals(Clip.ExitMode.CUT, read.clips().getFirst().exitMode());
        assertTrue(read.clips().getFirst().contains(5));
    }

    @Test
    void seamRestartRoundTripsAndAutoWritesNoKey() {
        var track = new ControlTrack();
        track.clips().add(new Clip(5, 20, 1).seamMode(Clip.SeamMode.RESTART));
        track.clips().add(new Clip(30, 10, 1));
        var tag = track.writeData(null);
        var clips = tag.getListOrEmpty("clips");
        assertEquals("restart", clips.getCompoundOrEmpty(0).getStringOr("seam", ""));
        assertFalse(clips.getCompoundOrEmpty(1).contains("seam"), "an auto clip saves exactly as before");
        var read = new ControlTrack();
        read.readData(null, tag);
        assertEquals(Clip.SeamMode.RESTART, read.clips().get(0).seamMode());
        assertEquals(Clip.SeamMode.AUTO, read.clips().get(1).seamMode());
    }
}
