package com.lowdragmc.photon.client;


/**
 * M0 stub (original in git history, 1.21 branch). The 1.21 version registered ~10
 * {@code ShaderInstance} core shaders (hdr_particle family + bloom chain) on
 * {@code RegisterShadersEvent}, plus a compute shader (catmull_rom) through LDLib2's shader
 * management — all removed APIs in 26.1.
 * <p>
 * TODO(M1): recreate as {@code PhotonPipelines} — {@code RenderPipeline}s registered via
 * {@code RegisterRenderPipelinesEvent}, shader assets as bare .vsh/.fsh with std140 includes.
 * TODO(M2): compute-shader (catmull_rom) replacement needs a 26.1 design decision (GpuDevice has
 * no compute abstraction) — discuss with the user.
 */
public class PhotonShaders {

    public static void init() {
        // no-op until M1: pipelines are registered declaratively via RegisterRenderPipelinesEvent
    }
}
