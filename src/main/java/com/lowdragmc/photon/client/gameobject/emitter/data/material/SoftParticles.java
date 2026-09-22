package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;

/**
 * Fades a fragment out as it approaches the opaque surface behind it, removing the hard line a quad shows
 * where it slices through the ground. Unity's Soft Particles / Unreal's DepthFade.
 *
 * <p>The GLSL half is {@code photon:soft_particle.glsl}, compiled into a material's fragment stage only
 * when {@link #isEnable()} — see {@code PhotonPipelines.SOFT_PARTICLE_DEFINE}. Top-level rather than
 * nested because {@link TextureMaterial} and {@link SpriteMaterial} are siblings.
 *
 * <p><b>26.1 shape.</b> 1.21 pushed these to the GPU per draw through {@code ShaderInstance} uniforms, and
 * had to write them on EVERY draw because one program served every material of a kind. In 26.1 the values
 * are part of the material's RenderType identity — they ride in the immutable {@code PhotonMaterial} UBO
 * ({@link com.lowdragmc.photon.client.render.PhotonMaterialUniforms}), so two materials with different
 * fade settings are two RenderTypes and neither can inherit the other's state. The depth texture is bound
 * by the drain from the frame's own capture, declared through {@code PhotonDrawInfo.Bindings.sceneSamplers}
 * — which is demand-driven, so a frame with no soft-particle material takes no depth copy.
 */
public class SoftParticles extends ToggleGroup {

    public enum Fade {
        /** Scale the whole result — right for additive, and for the premultiplied late-composite layer. */
        BOTH,
        /** Scale alpha only — right for classic {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}. */
        ALPHA_ONLY
    }

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
     * The {@code SoftParticleParams} vec4 the fragment stage reads: fade distance in blocks, exponent,
     * {@code 1} to fade alpha only, and a trailing {@code 1} when it is on at all.
     *
     * <p>Disabled returns the neutral value rather than nothing, so a RenderType built from an OFF
     * material is still value-distinct from an ON one and the two never share a uniform buffer.</p>
     */
    public float[] params() {
        if (!isEnable()) {
            return new float[]{1f, 1f, 0f, 0f};
        }
        return new float[]{Math.max(distance, 1e-5f), Math.max(power, 1e-5f),
                fade == Fade.ALPHA_ONLY ? 1f : 0f, 1f};
    }
}
