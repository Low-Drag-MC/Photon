package com.lowdragmc.photon.client.fx.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Sections and the release setting round-trip, and a timeline without them saves exactly as before. */
class TimelineSectionsNbtTest {

    private static Timeline circle() {
        var timeline = new Timeline();
        timeline.sections().add(new TimelineSection("Appear", 0));
        timeline.sections().add(new TimelineSection("Idle", 20).repeat(TimelineSection.FOREVER));
        timeline.sections().add(new TimelineSection("Charge", 40).repeat(3).then(TimelineSection.Then.GOTO).target("Idle"));
        timeline.sections().add(new TimelineSection("Vanish", 60).then(TimelineSection.Then.END));
        timeline.releaseSection("Vanish").releaseNow(false);
        return timeline;
    }

    @Test
    void sectionsAndReleaseRoundTrip() {
        var read = new Timeline();
        read.deserializeNBT(null, circle().serializeNBT(null));
        assertEquals(List.of("Appear", "Idle", "Charge", "Vanish"), read.sections().stream().map(TimelineSection::name).toList());
        var idle = read.sections().get(1);
        assertEquals(20, idle.start());
        assertEquals(TimelineSection.FOREVER, idle.repeat());
        var charge = read.sections().get(2);
        assertEquals(3, charge.repeat());
        assertEquals(TimelineSection.Then.GOTO, charge.then());
        assertEquals("Idle", charge.target());
        assertEquals(TimelineSection.Then.END, read.sections().get(3).then());
        assertEquals("Vanish", read.releaseSection());
        assertFalse(read.releaseNow());
    }

    @Test
    void aTimelineWithoutSectionsWritesNothingNew() {
        var tag = new Timeline().serializeNBT(null);
        assertFalse(tag.contains("sections"));
        assertFalse(tag.contains("release"));
        var read = new Timeline();
        read.deserializeNBT(null, tag);
        assertTrue(read.sections().isEmpty());
        assertNull(read.releaseSection());
        assertTrue(read.releaseNow());
    }

    @Test
    void defaultsAreLeftOut() {
        var timeline = new Timeline();
        timeline.sections().add(new TimelineSection("A", 0));
        var sectionTag = timeline.serializeNBT(null).getListOrEmpty("sections").getCompoundOrEmpty(0);
        assertFalse(sectionTag.contains("repeat"));
        assertFalse(sectionTag.contains("then"));
        assertFalse(sectionTag.contains("target"));
    }

    @Test
    void copyAndInsertTimeCarrySections() {
        var copy = circle().copy();
        assertEquals(4, copy.sections().size());
        assertEquals("Vanish", copy.releaseSection());
        copy.insertTime(30, 10);
        assertEquals(20, copy.sections().get(1).start(), "before the insertion point: unchanged");
        assertEquals(50, copy.sections().get(2).start());
        assertEquals(70, copy.sections().get(3).start());
        assertEquals(40, circle().sections().get(2).start(), "the copy is independent");
    }
}
