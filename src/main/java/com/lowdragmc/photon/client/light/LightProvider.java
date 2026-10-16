package com.lowdragmc.photon.client.light;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Asked once per frame, before culling, for the lights it wants that frame. */
@OnlyIn(Dist.CLIENT)
@FunctionalInterface
public interface LightProvider {
    void submitLights(LightSink sink, float partialTick);
}
