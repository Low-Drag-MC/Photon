package com.lowdragmc.photon.uitest;

import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL31;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Declared samplers or texel buffers a linked program does not use, and blocks it uses undeclared: both still render,
 * so only the linked program shows them. The GL backend's program is read back; on Vulkan, which keeps every
 * declaration, this is only the compile check.
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
        var names = new HashSet<>(BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts()));
        for (var uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) names.add(uniform.name());
        return names;
    }

    /** Empty when {@code pipeline} links and declares exactly what it uses. */
    static List<String> problems(RenderPipeline pipeline) {
        var problems = new ArrayList<String>();
        var compiled = RenderSystem.getDevice().precompilePipeline(pipeline);
        if (!compiled.isValid()) {
            problems.add("did not compile (the GLSL error is in the log)");
            return problems;
        }
        if (!(compiled instanceof GlRenderPipeline gl)) return problems;
        var live = gl.program().getUniforms().keySet();
        for (var sampler : BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts())) {
            if (!live.contains(sampler)) problems.add("sampler " + sampler + " unused");
        }
        for (var uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
            if (uniform.type() == UniformType.TEXEL_BUFFER && !live.contains(uniform.name())) {
                problems.add("texel buffer " + uniform.name() + " unused");
            }
        }
        int program = gl.program().getProgramId();
        int blocks = GL31.glGetProgrami(program, GL31.GL_ACTIVE_UNIFORM_BLOCKS);
        for (int i = 0; i < blocks; i++) {
            var name = GL31.glGetActiveUniformBlockName(program, i);
            if (!live.contains(name)) problems.add("block " + name + " used but not declared");
        }
        return problems;
    }
}
