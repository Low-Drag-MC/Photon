package com.lowdragmc.photon.client.gameobject.emitter.data.noise;

import org.joml.Vector3f;

/** Per-particle simulation state. Rendering only reads these values; it never advances noise. */
public final class NoiseParticleState {
    public final Vector3f velocity = new Vector3f();
    public final Vector3f rotation = new Vector3f();
    public final Vector3f size = new Vector3f(1);

    public void update(Vector3f noise, float positionAmount, float rotationAmount, float sizeAmount,
                       boolean rotation3D, float dt) {
        // Photon velocities and dt are in ticks; Noise 2 uses seconds and degrees/second.
        velocity.set(noise).mul(positionAmount / 20f);
        // Rotation consumes normalized curl (half the positional field), measured in degrees/s.
        float angle = rotationAmount * (float) (Math.PI / 180) * dt / 40f;
        if (rotation3D) {
            rotation.fma(angle, noise);
        } else {
            rotation.z += noise.z * angle;
        }
        // Size consumes the same normalized amplitude as rotation, using the X channel.
        size.set(Math.max(0, 1 + noise.x * 0.5f * sizeAmount));
    }
}
