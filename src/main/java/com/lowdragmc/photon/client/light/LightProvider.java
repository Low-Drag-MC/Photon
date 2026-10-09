package com.lowdragmc.photon.client.light;


/** Asked once per frame, before culling, for the lights it wants that frame. */
@FunctionalInterface
public interface LightProvider {
    void submitLights(LightSink sink, float partialTick);
}
