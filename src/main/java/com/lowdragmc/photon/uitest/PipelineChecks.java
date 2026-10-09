package com.lowdragmc.photon.uitest;

import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL31;

import java.util.ArrayList;
import java.util.List;

/**
 * Declared samplers or texel buffers a linked program does not use, and blocks it uses undeclared: both still render,
 * so only the linked program shows them (1.21 read the same off a {@code ShaderInstance}).
 */
final class PipelineChecks {
    private PipelineChecks() {
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
        for (var sampler : pipeline.getSamplers()) {
            if (!live.contains(sampler)) problems.add("sampler " + sampler + " unused");
        }
        for (var uniform : pipeline.getUniforms()) {
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
