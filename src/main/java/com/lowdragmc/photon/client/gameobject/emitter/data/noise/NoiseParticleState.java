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
        float angle = rotationAmount * (float) (Math.PI / 180) * dt / 20f;
        if (rotation3D) {
            rotation.fma(angle, noise);
        } else {
            rotation.z += noise.x * angle; // billboard roll is the Z component in Photon
        }
        // Noise's strength/remap axis controls do not select the particle's size dimensionality.
        size.set(Math.max(0, 1 + noise.x * sizeAmount));
    }
}
