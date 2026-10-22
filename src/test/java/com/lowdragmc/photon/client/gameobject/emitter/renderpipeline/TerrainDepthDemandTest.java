package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainDepthDemandTest {
    @Test
    void idleFramesDoNotRequestAnyCapture() {
        var demand = new TerrainDepthDemand();
        assertFalse(demand.wanted());
        for (int frame = 0; frame < 10; frame++) {
            demand.endFrame();
            assertFalse(demand.wanted());
        }
    }

    @Test
    void firstDrawOnlyRequestsTheFollowingFrame() {
        var demand = new TerrainDepthDemand();
        demand.request();
        assertFalse(demand.wanted());
        demand.endFrame();
        assertTrue(demand.wanted());
    }

    @Test
    void multipleMaterialsShareOneDemandAndIdleStopsIt() {
        var demand = new TerrainDepthDemand();
        for (int material = 0; material < 64; material++) demand.request();
        demand.endFrame();
        assertTrue(demand.wanted());
        demand.request();
        demand.endFrame();
        assertTrue(demand.wanted());
        demand.endFrame();
        assertFalse(demand.wanted());
    }

    @Test
    void scenesDoNotShareDemandAndUnloadClearsPendingRequests() {
        var world = new TerrainDepthDemand();
        var editor = new TerrainDepthDemand();
        world.request();
        world.endFrame();
        editor.endFrame();
        assertTrue(world.wanted());
        assertFalse(editor.wanted());
        world.request();
        world.reset();
        world.endFrame();
        assertFalse(world.wanted());
    }
}
