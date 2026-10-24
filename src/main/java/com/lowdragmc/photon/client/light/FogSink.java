package com.lowdragmc.photon.client.light;


/** Hands out a reset fog volume from a pool; it is only valid until the end of the frame. */
@FunctionalInterface
public interface FogSink {
    FogVolume next();
}
