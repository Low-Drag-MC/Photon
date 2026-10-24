package com.lowdragmc.photon.client.light;


/** Asked once per frame, before culling, for the fog volumes it wants that frame. */
@FunctionalInterface
public interface FogProvider {
    void submitFog(FogSink sink, float partialTick);
}
