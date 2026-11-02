package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The fullscreen draw behind bloom, post effects and format-converting copies: one NDC-covering triangle, with
 * {@code texCoord = Position.xy * 0.5 + 0.5} as compiled fullscreen graphs expect. Each draw opens its own pass, so it
 * runs only where no pass is open — {@code RenderFrameEvent.Pre}, Photon's frame seams, {@code AfterLevel}.
 */
public final class PhotonFullscreenPass {

    /** The shared vertex stage; pass pipelines pair it with their own fragment shader. */
    public static final String VERTEX_SHADER_PATH = "core/fullscreen";

    @Nullable
    private static GpuBuffer triangle;

    private PhotonFullscreenPass() {
    }

    /** No shader stages and no depth state (Vulkan needs a depth attachment for one). */
    public static RenderPipeline.Builder builder() {
        return RenderPipeline.builder()
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
    }

    /** A pipeline builder over the fullscreen triangle — samplers and state are the caller's. */
    public static RenderPipeline.Builder builder(Identifier fragmentShader) {
        return builder()
                .withVertexShader(Photon.id(VERTEX_SHADER_PATH))
                .withFragmentShader(fragmentShader);
    }

    public static ColorTargetState opaqueTarget(GpuFormat format) {
        return new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL);
    }

    /**
     * {@code bind} must bind everything the pipeline declares; no default uniforms are bound. False when nothing was
     * drawn — the pipeline does not compile — so a caller handing the target on knows it holds stale contents.
     */
    public static boolean draw(String label, RenderPipeline pipeline, GpuTextureView target,
                               Consumer<RenderPass> bind) {
        return draw(label, pipeline, target, null, bind);
    }

    public static boolean draw(String label, RenderPipeline pipeline, GpuTextureView target,
                               @Nullable ScissorState scissor, Consumer<RenderPass> bind) {
        return draw(label, pipeline, target, null, scissor, bind);
    }

    /**
     * With a depth attachment, for a pipeline whose state tests or writes depth (Vulkan requires the pair). Opens its
     * own pass, so no pass may be open. A pipeline that does not compile is skipped (and reported where it failed).
     */
    public static boolean draw(String label, RenderPipeline pipeline, GpuTextureView target,
                               @Nullable GpuTextureView depth, @Nullable ScissorState scissor,
                               Consumer<RenderPass> bind) {
        // allocated before the pass opens
        var geometry = triangle();
        var compiled = PhotonPipelines.compiled(pipeline);
        if (compiled == null) {
            return false;
        }
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> label, target, Optional.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(compiled);
            if (scissor != null && scissor.enabled()) {
                pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            }
            bind.accept(pass);
            pass.setVertexBuffer(0, geometry.slice());
            pass.draw(3, 1, 0, 0);
        }
        return true;
    }

    private static GpuBuffer triangle() {
        if (triangle == null) {
            var bytes = MemoryUtil.memAlloc(3 * 3 * Float.BYTES);
            try {
                bytes.putFloat(-1).putFloat(-1).putFloat(0)
                        .putFloat(3).putFloat(-1).putFloat(0)
                        .putFloat(-1).putFloat(3).putFloat(0)
                        .flip();
                triangle = RenderSystem.getDevice().createBuffer(() -> "Photon fullscreen triangle",
                        GpuBuffer.USAGE_VERTEX, bytes);
            } finally {
                MemoryUtil.memFree(bytes);
            }
        }
        return triangle;
    }

    // ---- format-converting copies ---------------------------------------------------------------

    private static final Map<GpuFormat, RenderPipeline> COPY_PIPELINES = new ConcurrentHashMap<>();

    private static RenderPipeline copyPipeline(GpuFormat format) {
        return COPY_PIPELINES.computeIfAbsent(format, f -> builder(Photon.id("core/photon_copy"))
                .withLocation(Photon.id("pipeline/copy_" + f.name().toLowerCase(Locale.ROOT)))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(opaqueTarget(f))
                .build());
    }

    public static void precompileCopy(GpuFormat format) {
        PhotonPipelines.compile(copyPipeline(format));
    }

    /** A same-size copy across formats (e.g. RGBA8 ↔ RGBA16F), which {@code copyTextureToTexture} cannot do. */
    public static void copy(String label, GpuTextureView source, GpuTextureView destination,
                            @Nullable ScissorState scissor) {
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        draw(label, copyPipeline(destination.texture().getFormat()), destination, scissor,
                pass -> pass.setUniform("InSampler", source, sampler));
    }
}
