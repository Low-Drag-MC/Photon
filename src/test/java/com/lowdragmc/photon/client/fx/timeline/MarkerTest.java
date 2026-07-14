package com.lowdragmc.photon.client.fx.timeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** MC-free unit tests for {@link Marker} (value copy + ripple-insert shifting). */
class MarkerTest {

    @Test
    void insertTimeShiftsMarkersAtOrAfter() {
        var before = new Marker(5, "a");
        var at = new Marker(10, "b");
        var after = new Marker(20, "c");
        var markers = new ArrayList<>(List.of(before, at, after));
        Marker.insertTime(markers, 10, 4);
        assertEquals(5, before.tick(), "a marker before the insertion point stays");
        assertEquals(14, at.tick(), "a marker exactly at the point shifts");
        assertEquals(24, after.tick(), "a marker after the point shifts");
    }

    @Test
    void insertTimeNegativeDeltaIsTheInverse() {
        var m = new Marker(20, "m");
        var markers = List.of(m);
        Marker.insertTime(markers, 10, 4);
        Marker.insertTime(markers, 10, -4);
        assertEquals(20, m.tick());
    }

    @Test
    void copyIsIndependentValueCopy() {
        var m = new Marker(3, "x");
        var copy = m.copy();
        assertNotSame(m, copy);
        assertEquals(3, copy.tick());
        assertEquals("x", copy.name());
        copy.tick(9);
        assertEquals(3, m.tick(), "mutating the copy must not affect the original");
    }
}
