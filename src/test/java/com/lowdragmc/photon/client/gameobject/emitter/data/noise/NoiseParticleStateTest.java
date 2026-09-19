package com.lowdragmc.photon.client.gameobject.emitter.data.noise;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoiseParticleStateTest {
    @Test
    void rotationAndDisplacementUseSecondsAndAreIndependentOfStepCount() {
        var whole = new NoiseParticleState();
        var split = new NoiseParticleState();
        var noise = new Vector3f(0.5f, -0.25f, 0.1f);
        whole.update(noise, 2, 90, 0, true, 20);
        var displacement = new Vector3f();
        for (int i = 0; i < 80; i++) {
            split.update(noise, 2, 90, 0, true, 0.25f);
            displacement.fma(0.25f, split.velocity);
        }
        assertTrue(whole.rotation.equals(split.rotation, 1e-5f));
        assertEquals(Math.PI / 8, whole.rotation.x, 1e-6);
        assertTrue(new Vector3f(whole.velocity).mul(20).equals(displacement, 1e-5f));
        assertEquals(1, displacement.x, 1e-5);
    }

    @Test
    void sizeIsRelativeAndDoesNotAccumulateAcrossUpdates() {
        var state = new NoiseParticleState();
        for (int i = 0; i < 40; i++) state.update(new Vector3f(0.5f), 0, 0, 1, false, 1);
        var small = new Vector3f(0.1f).mul(state.size);
        var large = new Vector3f(10).mul(state.size);
        assertEquals(1.25f, small.x / 0.1f, 1e-6);
        assertEquals(1.25f, large.x / 10, 1e-6);
    }

    @Test
    void zeroPositionAmountStillAllowsBillboardRotationAndSize() {
        var state = new NoiseParticleState();
        state.update(new Vector3f(1, -0.5f, 0.25f), 0, 90, 0.5f, false, 20);
        assertEquals(0, state.velocity.lengthSquared(), 0f);
        assertEquals(0, state.rotation.x);
        assertEquals(0, state.rotation.y);
        assertEquals(Math.PI / 16, state.rotation.z, 1e-6);
        assertEquals(new Vector3f(1.25f), state.size);
    }

    @Test
    void negativeSizeMultiplierIsClampedWithoutDistortingTheParticle() {
        var state = new NoiseParticleState();
        state.update(new Vector3f(-4, 1, 0.25f), 1, 0, 1, true, 1);
        assertEquals(new Vector3f(), state.size);
    }

    @Test
    void zeroDurationDoesNotAdvanceRotation() {
        var state = new NoiseParticleState();
        state.update(new Vector3f(1), 1, 90, 1, false, 0);
        assertEquals(new Vector3f(), state.rotation);
    }
}
