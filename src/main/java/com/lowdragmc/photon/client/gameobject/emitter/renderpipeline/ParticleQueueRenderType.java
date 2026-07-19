package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;


/**
 * M0 stub (original in git history, 1.21 branch). In 1.21 this was a {@code ParticleRenderType}
 * whose {@code begin()} returned the {@link RenderPassPipeline} to swap vanilla's particle buffer
 * for Photon's pipeline. 26.1's {@code ParticleRenderType} is a plain {@code record(String name)}
 * with no hooks.
 * <p>
 * TODO(M1): replaced by a registered Photon {@code ParticleGroup} (RegisterParticleGroupsEvent)
 * whose extractRenderState feeds Photon's own render path. Until then FX objects use
 * {@code ParticleRenderType.NO_RENDER} (tick-only, never rendered).
 */
public final class ParticleQueueRenderType {
    private ParticleQueueRenderType() {
    }
}
