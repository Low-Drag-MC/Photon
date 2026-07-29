package com.lowdragmc.photon.client.render;

import net.minecraft.client.particle.ParticleRenderType;

/**
 * Photon's particle-engine group keys. 26.1 buckets particles into {@code ParticleGroup}s keyed by
 * {@code ParticleRenderType} <b>identity</b> (IdentityHashMap in ParticleEngine), so these must stay
 * shared singletons.
 */
public final class PhotonParticleRenderTypes {

    /** Every Photon FX object (emitters and helpers alike) lives in this single group; rendering is
     *  batched per material during extraction, not per group. */
    public static final ParticleRenderType FX = new ParticleRenderType("photon:fx");

    private PhotonParticleRenderTypes() {
    }
}
