package com.lowdragmc.photon.client.gameobject.emitter.renderpipeline;


import javax.annotation.Nullable;

/**
 * M0 stub (original in git history, 1.21 branch). The 1.21 implementation extended
 * {@code BufferBuilder} and hijacked the vanilla particle render path
 * ({@code ParticleRenderType.begin} / {@code BufferBuilder.build()}), running Photon's whole
 * multi-pass HDR pipeline (scene copy → shaded/wireframe/mask passes → post effects → blit back)
 * from inside {@code ParticleEngine.render}. That contract no longer exists in 26.1
 * (extract → submit → feature-render, RenderPipeline/RenderPass, no ShaderInstance).
 * <p>
 * TODO(M1/M3): rebuilt as Photon-owned rendering — extraction produces render states, regular
 * materials render via {@code SubmitCustomGeometryEvent}, HDR/mask/post via
 * {@code FrameGraphSetupEvent} frame passes. See the migration plan.
 */
public class RenderPassPipeline {

    /** 1.21: the pipeline currently drawing (render-thread state). Always null until M1. */
    @Nullable
    public static RenderPassPipeline getCurrent() {
        return null;
    }

    /** 1.21: dropped the cached HDR draw target on window resize (driven by {@code MinecraftMixin}). */
    public static void markDrawTargetDirty() {
    }

    /** 1.21 seam: emitters piped their live particles into the pass during {@code Particle.render}.
     *  Dead until the M1 extraction replaces it; kept so the emitters' (dormant) render methods compile. */
    public void pipeQueue(PhotonFXRenderPass pass, java.util.Collection<?> particles) {
    }
}
