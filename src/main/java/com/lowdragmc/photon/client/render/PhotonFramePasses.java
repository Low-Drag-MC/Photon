package com.lowdragmc.photon.client.render;

/**
 * Photon's own frame passes — bloom, the FX layer composite, the stacked-distortion bend — compiled ahead of the first
 * frame that needs them. A pipeline compiles on its first draw (shaderc on Vulkan), and a resource reload drops every
 * compiled one, so without this the first effect after each reload stalls on them.
 * Render thread, outside any pass.
 */
public final class PhotonFramePasses {
    private PhotonFramePasses() {
    }

    public static void preload() {
        PhotonBloom.preload();
        PhotonFXLayer.preload();
        StackedDistortion.preload();
    }
}
