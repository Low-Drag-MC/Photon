package com.lowdragmc.photon.client.render;

import com.lowdragmc.kilagraph.rendertype.runtime.KGPipelines;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

/**
 * Frees what was compiled for a pipeline Photon will never draw again, which the pipeline cache would otherwise keep
 * until the next shader reload (a live-edited KilaMaterial compiles one per edit). Render thread only.
 */
final class PhotonPipelineReleases {

    private PhotonPipelineReleases() {
    }

    static void release(RenderPipeline pipeline) {
        RenderSystem.assertOnRenderThread();
        KGPipelines.evict(pipeline);
        PhotonOit.release(pipeline);
    }
}
