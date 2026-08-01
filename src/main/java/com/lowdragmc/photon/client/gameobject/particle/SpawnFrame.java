package com.lowdragmc.photon.client.gameobject.particle;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * An emitter, as a coordinate system, expressed in its own simulation space — the frame a particle was
 * born into.
 *
 * <p>Values an author gives "in the emitter's axes" (velocity-over-lifetime, force-over-lifetime) and
 * anything emitter-relative (an orbital pivot, the local-position GPU channel) live in this frame.
 * Simulation space is NOT that frame: outside {@code Local} it is the world, where a particle's stored
 * position is an absolute coordinate — reading it as emitter-relative drops an orbit pivot on the world
 * origin, hundreds of blocks away.
 *
 * <p>A particle keeps the frame it was born with rather than following the emitter, so an emitter that
 * moves or turns cannot re-aim particles it deliberately left behind in the world. That also matches
 * the pre-2.2.0 releases, where {@code World} simulation space was the emitter's spawn-time transform
 * rather than the identity.
 *
 * <p>Immutable and shared: every particle emitted while the emitter's transform is unchanged points at
 * the same instance (see {@code ParticleEmitter#currentSpawnFrame}), so this costs one reference per
 * particle instead of two matrices. A {@code null} frame means identity — in {@code Local} simulation
 * space the emitter's frame IS simulation space and no conversion is needed at all.
 */
public final class SpawnFrame {

    private final Matrix4f emitterToSim;
    private final Matrix4f simToEmitter;

    /**
     * @param emitterToWorld the emitter's {@code localToWorldMatrix} at spawn
     * @param worldToEmitter its {@code worldToLocalMatrix} at spawn
     * @param worldToSim     simulation space's {@code worldToLocal}
     * @param simToWorld     simulation space's {@code localToWorld}
     */
    public SpawnFrame(Matrix4f emitterToWorld, Matrix4f worldToEmitter, Matrix4f worldToSim, Matrix4f simToWorld) {
        // Copied, never referenced: Transform rebuilds its cached matrices in place of the old ones, so
        // holding a reference would silently un-freeze the frame. The inverse is composed from the two
        // already-cached matrices rather than inverted.
        this.emitterToSim = new Matrix4f(worldToSim).mul(emitterToWorld);
        this.simToEmitter = new Matrix4f(worldToEmitter).mul(simToWorld);
    }

    /** The emitter's own origin in simulation space — the translation column of {@code emitterToSim}. */
    public float originX() {
        return emitterToSim.m30();
    }

    public float originY() {
        return emitterToSim.m31();
    }

    public float originZ() {
        return emitterToSim.m32();
    }

    /** Rotate a direction from the emitter's axes into simulation space (in place). */
    public Vector3f emitterDirToSim(Vector3f direction) {
        return emitterToSim.transformDirection(direction);
    }

    /** Rotate a direction from simulation space into the emitter's axes (in place). */
    public Vector3f simDirToEmitter(Vector3f direction) {
        return simToEmitter.transformDirection(direction);
    }
}
