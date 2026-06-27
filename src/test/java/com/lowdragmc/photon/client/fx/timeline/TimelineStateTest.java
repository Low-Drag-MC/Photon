package com.lowdragmc.photon.client.fx.timeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MC-free unit tests for the per-object timeline resolution rule (two axes: lifecycle/tick vs.
 * visibility/render), including the tricky combined activator+control case (point 5).
 */
class TimelineStateTest {

    @Test
    void noTracksIsAlwaysActiveAndVisible() {
        var s = TimelineState.resolve(false, false, false, false);
        assertTrue(s.tick(), "default: ticks");
        assertTrue(s.render(), "default: renders");
    }

    @Test
    void activatorOnlyCouplesTickAndRender() {
        var active = TimelineState.resolve(true, true, false, false);
        assertTrue(active.tick());
        assertTrue(active.render());

        var inactive = TimelineState.resolve(true, false, false, false);
        assertFalse(inactive.tick(), "outside activator clip: frozen");
        assertFalse(inactive.render(), "outside activator clip: hidden");
    }

    @Test
    void controlOnlyCouplesTickAndRender() {
        var inside = TimelineState.resolve(false, false, true, true);
        assertTrue(inside.tick());
        assertTrue(inside.render());

        var outside = TimelineState.resolve(false, false, true, false);
        assertFalse(outside.tick(), "outside control clip: not running");
        assertFalse(outside.render(), "outside control clip: hidden");
    }

    @Test
    void combinedControlDrivesTickActivatorDrivesRender() {
        // point 5: control(2-10s) + activator(4-5s). During 2-4s: control says alive, activator hides.
        var runningHidden = TimelineState.resolve(true, false, true, true);
        assertTrue(runningHidden.tick(), "control keeps it ticking even when activator-inactive");
        assertFalse(runningHidden.render(), "activator hides it");

        // during 4-5s: both active -> visible and running
        var visible = TimelineState.resolve(true, true, true, true);
        assertTrue(visible.tick());
        assertTrue(visible.render());

        // control clip ended (after 10s): not ticking regardless of activator
        var dead = TimelineState.resolve(true, true, true, false);
        assertFalse(dead.tick(), "no control clip -> not running");
        assertFalse(dead.render(), "nothing alive to render");
    }
}
