package com.lowdragmc.photon.client.fx.timeline;

import com.lowdragmc.photon.client.fx.timeline.property.ConfigPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
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
    void exprClipOverridesCurveInsideRange() {
        var p = singleChannelAt(0); // curve is a constant 0
        p.addExprClip(0, new ExprClip(2, 4, "t * 2 + 1")); // clip [2, 6), t is clip-local
        assertNull(p.exprClips(0).getFirst().error(), "a valid expression has no error");
        // outside the clip → the curve
        assertEquals(0f, p.sampleChannelValue(0, 0), 1e-4);
        assertEquals(0f, p.sampleChannelValue(0, 6), 1e-4, "end is exclusive → curve again");
        // inside the clip → the expression, with clip-local t
        assertEquals(1f, p.sampleChannelValue(0, 2), 1e-4, "local t=0 → 1");
        assertEquals(7f, p.sampleChannelValue(0, 5), 1e-4, "local t=3 → 7");
    }

    @Test
    void invalidExprClipFallsBackToCurve() {
        var p = singleChannelAt(5); // curve is a constant 5
        p.addExprClip(0, new ExprClip(0, 10, "sin(")); // malformed
        assertNotNull(p.exprClips(0).getFirst().error(), "a malformed expression reports a syntax error");
        assertEquals(5f, p.sampleChannelValue(0, 2), 1e-4, "falls back to the underlying curve");
    }

    @Test
    void steppedSampleHoldsTheLatestKeyframe() {
        var p = singleChannelAt(0);       // key at (0, 0)
        p.addKey(0, 10, 1);
        p.addKey(0, 20, 0);
        assertEquals(0f, p.sampleChannelStepped(0, -5), 1e-4, "before the first key → first value");
        assertEquals(0f, p.sampleChannelStepped(0, 5), 1e-4, "between (0) and (10) → holds 0");
        assertEquals(1f, p.sampleChannelStepped(0, 10), 1e-4, "exactly on a key → that key's value");
        assertEquals(1f, p.sampleChannelStepped(0, 15), 1e-4, "between (10) and (20) → holds 1");
        assertEquals(0f, p.sampleChannelStepped(0, 25), 1e-4, "after the last key → last value");
    }

    @Test
    void configIntPropertyRoundsSampledValue() {
        var type = new ConfigPropertyType("maxParticles", ConfigValueType.INT, "label");
        var p = new AnimatedProperty(type, new float[]{0}, AnimatedProperty.seedChannels(new float[]{0}), -1, 1);
        p.addKey(0, 10, 2.6f);
        assertEquals(0f, type.sample(p, 5)[0], 1e-4, "step-held 0, rounded");
        assertEquals(3f, type.sample(p, 10)[0], 1e-4, "2.6 rounds to 3");
        assertEquals(3f, type.sample(p, 20)[0], 1e-4, "held past the last key, rounded");
    }

    @Test
    void configBoolPropertyThresholdsAtHalf() {
        var type = new ConfigPropertyType("looping", ConfigValueType.BOOL, "label");
        assertEquals(0f, type.clampValue(0.3f), 1e-4, "< 0.5 → false");
        assertEquals(1f, type.clampValue(0.7f), 1e-4, ">= 0.5 → true");
        var p = new AnimatedProperty(type, new float[]{0}, AnimatedProperty.seedChannels(new float[]{0}), 0, 1);
        p.addKey(0, 10, 1);
        assertEquals(0f, type.sample(p, 5)[0], 1e-4, "held 0 before the toggle");
        assertEquals(1f, type.sample(p, 10)[0], 1e-4, "toggles to 1 at the key");
    }
}
