package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;


/**
 * M0 stub (original in git history, 1.21 branch). In 1.21 one instance was the batching unit per
 * (renderer settings, VertexFormat.Mode, VertexFormat): CPU path via Tesselator→MeshData→VBO,
 * instanced path via the raw-GL backend, plus wireframe and mask sub-passes with dedicated
 * materials.
 * <p>
 * TODO(M1/M2): the batching concept survives, re-expressed as Photon render-state batches keyed by
 * (material, RenderPipeline, vertex format) produced during extraction and drawn via
 * SubmitCustomGeometryEvent / Photon frame passes.
 */
public abstract class PhotonFXRenderPass {

    /** Invalidation hook (instanced layout/model changed). No-op until the M1/M2 render state exists. */
    public void clearInstance() {
    }
}
