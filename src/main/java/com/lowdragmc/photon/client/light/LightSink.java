package com.lowdragmc.photon.client.light;


/** Hands out a reset light from a pool; it is only valid until the end of the frame. */
@FunctionalInterface
public interface LightSink {
    DynamicLight next();
}
