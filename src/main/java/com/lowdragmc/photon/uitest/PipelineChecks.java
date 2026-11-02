package com.lowdragmc.photon.uitest;

import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
  * Declared samplers or texel buffers the compiled shaders lack (26.3 already rejects the opposite).
 */
final class PipelineChecks {
    private PipelineChecks() {
    }

    /** The instanced stream as the drain declares it with every record uploaded; null for the CPU path. */
    @Nullable
    static PhotonPipelines.InstancedGeometryKey fullGeometry(@Nullable PhotonPipelines.InstancedVariant variant) {
        return variant == null ? null : new PhotonPipelines.InstancedGeometryKey(variant, variant.layout, true,
                variant.usesCustomData);
    }

    /** The blocks, texel buffers and samplers {@code pipeline}'s layout declares: what a draw of it must bind. */
    static Set<String> declared(RenderPipeline pipeline) {
        var names = new HashSet<String>();
        for (var uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) names.add(uniform.name());
        return names;
    }

    /** Empty when {@code pipeline} compiles and declares exactly what it uses. */
    static List<String> problems(RenderPipeline pipeline) {
        var problems = new ArrayList<String>();
        if (!PhotonPipelines.compile(pipeline)) {
            problems.add("did not compile (the GLSL error is in the log)");
            return problems;
        }
        if (!(PhotonPipelines.compiled(pipeline) instanceof FrontendRenderPipeline compiled)) {
            return problems;
        }
        var live = compiled.uniformIndices().keySet();
        for (var uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
            if (live.contains(uniform.name())) continue;
            switch (uniform.type()) {
                case COMBINED_IMAGE_SAMPLER -> problems.add("sampler " + uniform.name() + " unused");
                case TEXEL_BUFFER -> problems.add("texel buffer " + uniform.name() + " unused");
                default -> {
                }
            }
        }
        return problems;
    }

    /** The uniforms reflected from {@code pipeline}'s compiled shaders, or null when it does not compile. */
    @Nullable
    static Set<String> live(RenderPipeline pipeline) {
        if (!PhotonPipelines.compile(pipeline)
                || !(PhotonPipelines.compiled(pipeline) instanceof FrontendRenderPipeline compiled)) {
            return null;
        }
        return new HashSet<>(compiled.uniformIndices().keySet());
    }
}
