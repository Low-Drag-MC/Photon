package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

import com.lowdragmc.photon.client.compat.iris.IrisDepthSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisDepthSourceTest {
    @Test
    void acceptsTheSameDepthAtTheSameResolution() {
        var source = new IrisDepthSource(7, 11, 1280, 720, 3);
        assertTrue(source.matches(11, 1280, 720, 3));
    }

    @Test
    void rejectsResizedRecreatedAndReplacedDepth() {
        var source = new IrisDepthSource(7, 11, 1280, 720, 3);
        assertFalse(source.matches(12, 1280, 720, 3));
        assertFalse(source.matches(11, 1920, 720, 3));
        assertFalse(source.matches(11, 1280, 1080, 3));
        assertFalse(source.matches(11, 1280, 720, 4));
    }
}
