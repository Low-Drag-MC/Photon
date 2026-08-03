package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.fx.timeline.property.ColorAnimatedProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MC-free unit tests for {@link ColorAnimatedProperty}'s pure {@code f(t) -> ARGB} sampling. Built with a
 * {@code null} type so no net.minecraft class is touched (only the ARGB bit math is exercised).
 */
class ColorAnimatedPropertyTest {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    private static int a(int c) { return (c >> 24) & 0xFF; }
    private static int r(int c) { return (c >> 16) & 0xFF; }
    private static int g(int c) { return (c >> 8) & 0xFF; }
    private static int b(int c) { return c & 0xFF; }

    @Test
    void emptyPropertySamplesOpaqueWhite() {
        var p = new ColorAnimatedProperty(null);
        assertEquals(0xFFFFFFFF, p.sampleColor(0));
        assertEquals(0xFFFFFFFF, p.sampleColor(100));
    }

    @Test
    void singleStopIsConstant() {
        var p = new ColorAnimatedProperty(null);
        p.addStop(5, RED);
        assertEquals(RED, p.sampleColor(-10));
        assertEquals(RED, p.sampleColor(5));
        assertEquals(RED, p.sampleColor(999));
    }

    @Test
    void twoStopsClampOutsideAndLerpInside() {
        var p = new ColorAnimatedProperty(null);
        p.addStop(0, RED);
        p.addStop(10, BLUE);
        // clamp to the edge colors outside the span
        assertEquals(RED, p.sampleColor(-1));
        assertEquals(BLUE, p.sampleColor(11));
        // endpoints exactly
        assertEquals(RED, p.sampleColor(0));
        assertEquals(BLUE, p.sampleColor(10));
        // midpoint: per-channel linear interpolation (rounded)
        var mid = p.sampleColor(5);
        assertEquals(255, a(mid), "alpha stays 255");
        assertEquals(128, r(mid), "red 255->0 at 0.5 rounds to 128");
        assertEquals(0, g(mid));
        assertEquals(128, b(mid), "blue 0->255 at 0.5 rounds to 128");
    }

    @Test
    void addStopKeepsTicksSorted() {
        var p = new ColorAnimatedProperty(null);
        p.addStop(10, BLUE);
        p.addStop(0, RED); // inserted before
        assertEquals(2, p.stops().size());
        assertEquals(0f, p.stops().get(0).tick, 1e-4);
        assertEquals(10f, p.stops().get(1).tick, 1e-4);
        assertEquals(RED, p.sampleColor(0));
    }

    @Test
    void gradientClipIsActiveInsideItsWindowEarliestWins() {
        var p = new ColorAnimatedProperty(null);
        var a = new GradientClip(0, 10, null);
        var b = new GradientClip(5, 10, null);
        p.gradientClips().add(a);
        p.gradientClips().add(b);
        assertSame(a, p.activeGradientClip(2), "only a contains 2");
        assertSame(a, p.activeGradientClip(7), "overlap [5,10): earliest (a) wins");
        assertNull(p.activeGradientClip(20), "past both");
    }

    @Test
    void keyframeTimesReportStopTicks() {
        var p = new ColorAnimatedProperty(null);
        p.addStop(3, RED);
        p.addStop(7, BLUE);
        var times = p.keyframeTimes();
        assertEquals(2, times.size());
        assertEquals(3.0, times.get(0), 1e-4);
        assertEquals(7.0, times.get(1), 1e-4);
    }
}
