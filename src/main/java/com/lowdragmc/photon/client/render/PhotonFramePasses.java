package com.lowdragmc.photon.client.render;

/**
 * Photon's own frame passes — bloom, the FX layer composite, the stacked-distortion bend — compiled ahead of the first
 * frame that needs them. 26.1 compiles a pipeline on its first draw, and a resource reload drops every compiled one, so
 * without this the first effect after each reload stalls on them; 1.21 rebuilt their programs in its reload listener.
 * Render thread, outside any pass.
 */
public final class PhotonFramePasses {
    private PhotonFramePasses() {
    }

    public static void preload() {
        PhotonBloom.preload();
        PhotonFXLayer.preload();
        if (PhotonFloatTextures.isSupported()) {
            StackedDistortion.preload();
        }
    }
}
