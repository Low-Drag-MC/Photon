package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Lets dynamic lights light the particle, e.g. smoke glowing orange next to a fireball. A billboard has
 * no normal, so light arrives from every direction, unshadowed. GLSL side: {@code photon:lit_particle.glsl}.
 */
@OnlyIn(Dist.CLIENT)
public class LitParticles extends ToggleGroup {

    @Configurable(name = "TextureMaterial.litParticles.intensity")
    @ConfigNumber(range = {0, 16})
    public float intensity = 1f;

    public void copyFrom(LitParticles other) {
        setEnable(other.isEnable());
        intensity = other.intensity;
    }

    /**
     * ⚠️ Every draw, enabled or not: one program serves every material of a kind. Off nulls the samplers,
     * the one value {@code ShaderInstance.apply} leaves unbound.
     */
    public void apply(ShaderInstance shader, MaterialContext context) {
        if (isEnable() && !context.isRenderingPreview() && DynamicLightRenderer.hasLights()) {
            DynamicLightRenderer.bindLights(shader);
            shader.safeGetUniform("LitParticleParams").set(1f, intensity, 0f, 0f);
            return;
        }
        //noinspection DataFlowIssue
        shader.setSampler("PhotonLightData", null);
        //noinspection DataFlowIssue
        shader.setSampler("PhotonClusterGrid", null);
        //noinspection DataFlowIssue
        shader.setSampler("PhotonClusterIndices", null);
        shader.safeGetUniform("LitParticleParams").set(0f, 0f, 0f, 0f);
    }
}
