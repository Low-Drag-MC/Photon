package com.lowdragmc.photon.client.render;

import java.util.List;

/** Geometry generation registered during extraction and run when the view prepares its frame. */
@FunctionalInterface
public interface PhotonBakeTask {
    void bake(PhotonViewSettings settings, List<PhotonWorldRenderState.DrawJob> out);
}
