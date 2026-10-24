package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.core.mixins.accessor.DeviceCachesAccessor;
import com.lowdragmc.photon.core.mixins.accessor.GpuDeviceAccessor;
import com.lowdragmc.photon.core.mixins.accessor.ShaderKeyAccessor;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Frees what a device compiled for a pipeline Photon will never draw again. Both backends keep compiled pipelines
 * and shader modules until the next resource reload; a material edited live (KilaMaterial) would otherwise leak one
 * program per edit. The entries leave the caches at once, so nothing can pick them up again, and are destroyed a few
 * frames later: Vulkan destroys a pipeline immediately, and up to two frames may still be recording or executing with
 * it. Render thread only.
 */
final class PhotonPipelineReleases {
    /** Frame boundaries a released pipeline waits: more than the frames MC keeps in flight. */
    private static final int FRAMES = 3;

    private record Pending(CompiledRenderPipeline compiled, List<Object> shaders, long frame) {
    }

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static long frame;

    private PhotonPipelineReleases() {
    }

    static void release(RenderPipeline pipeline) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.tryGetDevice();
        if (device == null || !(((GpuDeviceAccessor) device).photon$backend() instanceof DeviceCachesAccessor caches)) {
            return;
        }
        var compiled = caches.photon$pipelineCache().remove(pipeline);
        if (compiled == null) {
            return;
        }
        var defines = pipeline.getShaderDefines();
        var others = caches.photon$pipelineCache().keySet();
        var shaders = new ArrayList<>();
        caches.photon$shaderCache().entrySet().removeIf(entry -> {
            var key = (ShaderKeyAccessor) entry.getKey();
            var id = key.photon$id();
            if (!key.photon$defines().equals(defines) || !id.equals(shader(pipeline, key.photon$type()))) {
                return false;
            }
            // a module is shared by every pipeline with the same source and defines
            for (var other : others) {
                if (other.getShaderDefines().equals(defines) && id.equals(shader(other, key.photon$type()))) {
                    return false;
                }
            }
            shaders.add(entry.getValue());
            return true;
        });
        PENDING.add(new Pending((CompiledRenderPipeline) compiled, shaders, frame));
    }

    static void endFrame() {
        frame++;
        while (!PENDING.isEmpty() && frame - PENDING.peek().frame() >= FRAMES) {
            destroy(PENDING.poll());
        }
    }

    private static void destroy(Pending pending) {
        switch (pending.compiled()) {
            case GlRenderPipeline gl when gl.isValid() -> gl.program().close();
            case Destroyable vulkan -> vulkan.destroy();
            default -> {
            }
        }
        for (var shader : pending.shaders()) {
            if (shader == GlShaderModule.INVALID_SHADER || shader == IntermediaryShaderModule.INVALID) {
                continue;
            }
            try {
                ((AutoCloseable) shader).close();
            } catch (Exception ignored) {
            }
        }
    }

    private static Identifier shader(RenderPipeline pipeline, ShaderType type) {
        return type == ShaderType.VERTEX ? pipeline.getVertexShader() : pipeline.getFragmentShader();
    }
}
