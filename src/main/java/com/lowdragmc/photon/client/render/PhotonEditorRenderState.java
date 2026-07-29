package com.lowdragmc.photon.client.render;

/**
 * Editor-scene render flags (SceneView's shaded/wireframe/bloom topbar toggles). Only consulted
 * when extraction runs under a {@code SceneCamera} (editor scenes) — world rendering ignores them.
 * Static like the 1.21 RenderPassPipeline flag: there is at most one interactive editor scene.
 */
public final class PhotonEditorRenderState {

    public static volatile boolean drawShaded = true;
    public static volatile boolean drawWireframe = false;
    public static volatile boolean bloomEnabled = true;

    private PhotonEditorRenderState() {
    }
}