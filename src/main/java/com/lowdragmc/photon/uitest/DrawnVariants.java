package com.lowdragmc.photon.uitest;

import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.util.Set;

/** Which geometry a pipeline draws, read off the defines it was built with — what 1.21 named a MaterialContext. */
final class DrawnVariants {
    private DrawnVariants() {
    }

    /** The instanced family's selector define, {@code PHOTON_CPU_DATA} for the CPU path with records, else "". */
    static String of(RenderPipeline pipeline) {
        Set<String> flags = pipeline.getShaderDefines().flags();
        if (flags.contains(PhotonPipelines.CPU_DATA_DEFINE)) return PhotonPipelines.CPU_DATA_DEFINE;
        for (var variant : PhotonPipelines.InstancedVariant.values()) {
            var selector = variant.defines.getFirst();
            if (flags.contains(selector)) return selector;
        }
        return "";
    }

    /** Whether {@code pipeline} is one of KilaMaterial's. */
    static boolean isKila(RenderPipeline pipeline) {
        return pipeline.getLocation().getPath().startsWith("pipeline/kila_");
    }

    /** Whether {@code pipeline} is a CustomShaderMaterial's, CPU or instanced. */
    static boolean isCustomShader(RenderPipeline pipeline) {
        var path = pipeline.getLocation().getPath();
        return path.startsWith("pipeline/custom_shader_") || path.startsWith("pipeline/custom_instanced_");
    }

    /** Whether {@code pipeline} is one of TextureMaterial's (any of the three fragment stages, CPU or instanced). */
    static boolean isHdrParticle(RenderPipeline pipeline) {
        return pipeline.getLocation().getPath().startsWith("pipeline/hdr_particle");
    }
}
