package com.lowdragmc.photon.client.gameobject.particle;

import com.lowdragmc.photon.client.gameobject.particle.ParticleLifetime.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleLifetimeTest {
    private static final float EPS = 1e-5f;

    @Test
    void onceRunsZeroToOneAndHolds() {
        assertEquals(0, ParticleLifetime.t(Mode.Once, 0, 20), EPS);
        assertEquals(0.5f, ParticleLifetime.t(Mode.Once, 10, 20), EPS);
        assertEquals(1, ParticleLifetime.t(Mode.Once, 20, 20), EPS);
        assertEquals(1, ParticleLifetime.t(Mode.Once, 35, 20), EPS);
    }

    @Test
    void loopStartsOverEveryLifetime() {
        assertEquals(0.25f, ParticleLifetime.t(Mode.Loop, 5, 20), EPS);
        assertEquals(0.95f, ParticleLifetime.t(Mode.Loop, 19, 20), EPS);
        assertEquals(0, ParticleLifetime.t(Mode.Loop, 20, 20), EPS);
        assertEquals(0.05f, ParticleLifetime.t(Mode.Loop, 21, 20), EPS);
        assertEquals(0.5f, ParticleLifetime.t(Mode.Loop, 1010, 20), EPS);
    }

    @Test
    void pingPongGoesUpThenDown() {
        assertEquals(0.25f, ParticleLifetime.t(Mode.PingPong, 5, 20), EPS);
        assertEquals(1, ParticleLifetime.t(Mode.PingPong, 20, 20), EPS);
        assertEquals(0.75f, ParticleLifetime.t(Mode.PingPong, 25, 20), EPS);
        assertEquals(0.05f, ParticleLifetime.t(Mode.PingPong, 39, 20), EPS);
        assertEquals(0, ParticleLifetime.t(Mode.PingPong, 40, 20), EPS);
        assertEquals(0.05f, ParticleLifetime.t(Mode.PingPong, 41, 20), EPS);
        assertEquals(0.25f, ParticleLifetime.t(Mode.PingPong, 45, 20), EPS);
        assertEquals(0.75f, ParticleLifetime.t(Mode.PingPong, 65, 20), EPS);
    }

    @Test
    void pingPongIsContinuousAtItsTurns() {
        for (var turn : new int[]{20, 40, 60}) {
            var before = ParticleLifetime.t(Mode.PingPong, turn - 0.01f, 20);
            var after = ParticleLifetime.t(Mode.PingPong, turn + 0.01f, 20);
            assertTrue(Math.abs(before - after) < 0.01f, "jump at " + turn + ": " + before + " -> " + after);
        }
    }

    @Test
    void withoutALifetimeTIsZero() {
        for (var mode : Mode.values()) {
            assertEquals(0, ParticleLifetime.t(mode, 50, 0), EPS);
        }
    }

    @Test
    void stoppingEndsTheCurrentCycle() {
        assertEquals(20, ParticleLifetime.endOfCycle(5, 20), EPS);
        assertEquals(40, ParticleLifetime.endOfCycle(20, 20), EPS);
        assertEquals(60, ParticleLifetime.endOfCycle(59.5f, 20), EPS);
    }

    @Test
    void theLastCycleEndsOnItsFarEnd() {
        assertEquals(1, ParticleLifetime.finalT(Mode.Loop, 40, 20), EPS);
        assertEquals(1, ParticleLifetime.finalT(Mode.PingPong, 20, 20), EPS);
        assertEquals(0, ParticleLifetime.finalT(Mode.PingPong, 40, 20), EPS);
        assertEquals(1, ParticleLifetime.finalT(Mode.PingPong, 60, 20), EPS);
    }

    @Test
    void anEndingParticleHoldsItsLastValue() {
        assertEquals(0.75f, ParticleLifetime.t(Mode.Loop, 15, 20, 20), EPS);
        assertEquals(1, ParticleLifetime.t(Mode.Loop, 20, 20, 20), EPS);
        assertEquals(1, ParticleLifetime.t(Mode.Loop, 23, 20, 20), EPS);
        assertEquals(0, ParticleLifetime.t(Mode.PingPong, 41, 20, 40), EPS);
        // not ending: the plain cycle
        assertEquals(0, ParticleLifetime.t(Mode.Loop, 20, 20, -1), EPS);
    }

    @Test
    void onlyLoopAndPingPongAreCyclic() {
        assertFalse(Mode.Once.cyclic());
        assertTrue(Mode.Loop.cyclic());
        assertTrue(Mode.PingPong.cyclic());
    }
}
