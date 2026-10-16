package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;

final class TerrainDepthDemand {
    private boolean requested;
    private boolean wanted;

    void request() {
        requested = true;
    }

    boolean wanted() {
        return wanted;
    }

    void endFrame() {
        wanted = requested;
        requested = false;
    }

    void reset() {
        wanted = requested = false;
    }
}
