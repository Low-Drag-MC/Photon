package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;

import java.util.Set;

/**
 * Lets dynamic lights light the particle, e.g. smoke glowing orange next to a fireball. A billboard has
 * no normal, so light arrives from every direction; with voxel shadows, blocks between a shadowed light and the
 * particle stop it. GLSL side: {@code photon:lit_particle.glsl}, which any shader can include.
 * <p>
 * 26.1: lighting is a pipeline variant ({@code PhotonPipelines.LIT_DEFINE}), since a pipeline declaring the light
 * buffers must bind them at every draw. The intensity rides in the {@code PhotonMaterial} block.
 */
public class LitParticles extends ToggleGroup {
    public static final String INCLUDE = "photon:lit_particle.glsl";
    /** The include's 1.21 json entries: a shader listing them is lit; they never become material values. */
    public static final Set<String> JSON_SAMPLERS = Set.of("PhotonLightData", "PhotonClusterGrid",
            "PhotonClusterIndices", "PhotonVisibilityAtlas");
    public static final Set<String> JSON_UNIFORMS = Set.of("PhotonClusterInfo", "PhotonClusterView",
            "LitParticleParams", "PhotonVisibilityInfo", "PhotonViewInverse");

    @Configurable(name = "TextureMaterial.litParticles.intensity")
    @ConfigNumber(range = {0, 16})
    public float intensity = 1f;

    public void copyFrom(LitParticles other) {
        setEnable(other.isEnable());
        intensity = other.intensity;
    }

    /** What the material block carries: 0 is unlit, which is also what keeps the pipeline free of the lights. */
    public float blockIntensity() {
        return isEnable() && intensity > 0 ? intensity : 0f;
    }

    /** Whether {@code name} is a sampler or uniform the include declares. */
    public static boolean declares(String name) {
        return JSON_SAMPLERS.contains(name) || JSON_UNIFORMS.contains(name);
    }

    /** Whether generated or hand-written GLSL pulls the lights in. */
    public static boolean includedBy(String glsl) {
        return glsl.contains("#moj_import <" + INCLUDE + ">");
    }
}
