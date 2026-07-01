package com.lowdragmc.photon.client.fx.timeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MC-free unit tests for the keyframe-editing core of {@link AnimatedProperty} (record mode's
 * {@code putKey}). Builds the property with a {@code null} type so no net.minecraft class is touched.
 */
class AnimatedPropertyTest {

    private static AnimatedProperty singleChannelAt(float v) {
        return new AnimatedProperty(null, new float[]{v}, AnimatedProperty.seedChannels(new float[]{v}), v - 1, v + 1);
    }

    @Test
    void putKeyReusesTheKeyAtTheSameTick() {
        var p = singleChannelAt(0); // one key at (0, 0)
        p.putKey(0, 0, 5);
        assertEquals(1, p.keyCount(0), "no new key added at the same tick");
        assertEquals(5f, p.key(0, 0).y, 1e-4, "the existing key's value is updated");
    }

    @Test
    void putKeyAddsAtNewTickThenReusesIt() {
        var p = singleChannelAt(0); // one key at (0, 0)
        var i = p.putKey(0, 10, 3);
        assertEquals(2, p.keyCount(0), "a new key is added at a fresh tick");
        p.putKey(0, 10, 7);
        assertEquals(2, p.keyCount(0), "re-keying the same tick reuses it");
        assertEquals(7f, p.key(0, i).y, 1e-4);
    }

    @Test
    void expressionChannelSamplesFunctionOfTime() {
        var p = singleChannelAt(0);
        p.setMode(0, AnimatedProperty.ChannelMode.EXPRESSION);
        p.setExpression(0, "t * 2 + 1");
        assertNull(p.exprError(0), "a valid expression has no error");
        assertEquals(1f, p.sampleChannelValue(0, 0), 1e-4);
        assertEquals(7f, p.sampleChannelValue(0, 3), 1e-4);
    }

    @Test
    void invalidExpressionReportsErrorAndFallsBackToBase() {
        var p = singleChannelAt(5); // base value 5
        p.setMode(0, AnimatedProperty.ChannelMode.EXPRESSION);
        p.setExpression(0, "sin("); // malformed
        assertNotNull(p.exprError(0), "a malformed expression reports a syntax error");
        assertEquals(5f, p.sampleChannelValue(0, 2), 1e-4, "falls back to the captured base value");
    }
}
