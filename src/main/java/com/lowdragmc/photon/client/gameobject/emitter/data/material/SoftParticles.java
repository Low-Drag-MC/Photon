package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.RenderPassPipeline;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * Fades a fragment out as it approaches the opaque surface behind it, removing the hard line a quad shows
 * where it slices through the ground. Unity's Soft Particles / Unreal's DepthFade.
 *
 * <p>The GLSL half is {@code photon:soft_particle.glsl}, imported by every fragment shader whose material
 * carries one of these. Top-level rather than nested because {@link TextureMaterial} and
 * {@link SpriteMaterial} are siblings and {@link #apply} is not safe to keep two copies of.
 */
@OnlyIn(Dist.CLIENT)
public class SoftParticles extends ToggleGroup {

    public enum Fade {
        /** Scale the whole result — right for additive, and for the premultiplied late-composite layer. */
        BOTH,
        /** Scale alpha only — right for classic {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}. */
        ALPHA_ONLY
    }

    /** Render-thread scratch for the per-draw projection inverse; avoids an allocation in the draw path. */
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f();

    @Configurable(name = "TextureMaterial.softParticles.distance")
    @ConfigNumber(range = {0.001, 64})
    public float distance = 1f;
    @Configurable(name = "TextureMaterial.softParticles.power")
    @ConfigNumber(range = {0.01, 16})
    public float power = 1f;
    @Configurable(name = "TextureMaterial.softParticles.fade")
    public Fade fade = Fade.BOTH;

    public void copyFrom(SoftParticles other) {
        setEnable(other.isEnable());
        distance = other.distance;
        power = other.power;
        fade = other.fade;
    }

    /**
     * ⚠️ Call this on EVERY draw, enabled or not. One compiled program serves every material of a given
     * kind, so a material that skipped it would inherit whatever the previous one left in
     * {@code SoftParticleParams} and fade against a depth texture it never asked for.
     *
     * <p>Disabled costs one {@code glUniform4f} and a null sampler entry, and deliberately does not touch
     * {@link RenderPassPipeline}: asking it for the scene samplers is what triggers a full-screen depth
     * copy, and that copy is pull-based — so a frame nothing wants one in never pays for it, nor even
     * allocates the target.
     */
    public void apply(ShaderInstance shader, MaterialContext context) {
        int depthTexture = 0;
        // The material thumbnail draws outside any render pass, so there is no scene to fade against.
        if (isEnable() && !context.isRenderingPreview()) {
            var pipeline = RenderPassPipeline.getCurrent();
            if (pipeline != null) {
                depthTexture = pipeline.getSceneSamplers().depthTexture();
            }
        }
        // No capture this frame reads as "nothing is occluding", not as "everything is at the near plane" —
        // which is what sampling an unbound depth sampler looks like, and would make the particle vanish.
        if (depthTexture <= 0) {
            // null, not 0 or -1: a null entry is the only value ShaderInstance.apply SKIPS outright, and
            // skipping is the whole zero-cost claim — 0 would re-bind the unit every draw and -1 still
            // costs the uniform lookup. ShaderInstance.parseSamplerNode seeds the map with null itself, so
            // the inferred @NotNull is wrong. An enabled draw's texture may stay bound to a unit nothing
            // reads afterwards; it is pipeline-owned, so that is state, not a leak.
            //noinspection DataFlowIssue
            shader.setSampler(PhotonShaderCompiler.SCENE_DEPTH, null);
            shader.safeGetUniform("SoftParticleParams").set(1f, 1f, 0f, 0f);
            return;
        }
        shader.setSampler(PhotonShaderCompiler.SCENE_DEPTH, depthTexture);
        shader.safeGetUniform("U_InverseProjectionMatrix")
                .set(RenderSystem.getProjectionMatrix().invert(INVERSE_PROJECTION));
        shader.safeGetUniform("SoftParticleParams")
                .set(distance, power, fade == Fade.ALPHA_ONLY ? 1f : 0f, 1f);
    }
}
