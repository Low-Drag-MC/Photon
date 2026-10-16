package com.lowdragmc.photon.client.light;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Hands out a reset fog volume from a pool; it is only valid until the end of the frame. */
@OnlyIn(Dist.CLIENT)
@FunctionalInterface
public interface FogSink {
    FogVolume next();
}
