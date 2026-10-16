package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;
import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Lets dynamic lights light the particle, e.g. smoke glowing orange next to a fireball. A billboard has
 * no normal, so light arrives from every direction; with voxel shadows, blocks between a shadowed light and the
 * particle stop it. GLSL side: {@code photon:lit_particle.glsl}, which any shader can include and have bound
 * by {@link #bind}.
 */
@OnlyIn(Dist.CLIENT)
public class LitParticles extends ToggleGroup {
    public static final String INCLUDE = "photon:lit_particle.glsl";
    /** The samplers the include declares; a shader's json has to list them as {@code hdr_particle.json} does. */
    public static final List<String> SAMPLERS = List.of("PhotonLightData", "PhotonClusterGrid", "PhotonClusterIndices",
            "PhotonVisibilityAtlas");
    private static final String UNIFORMS_JSON = """
            [
              { "name": "PhotonClusterInfo", "type": "int", "count": 4, "values": [ 64, 1, 1, 1 ] },
              { "name": "PhotonClusterView", "type": "float", "count": 4, "values": [ 0.5, 1.0, 0.0, 0.0 ] },
              { "name": "LitParticleParams", "type": "float", "count": 4, "values": [ 0.0, 0.0, 0.0, 0.0 ] },
              { "name": "PhotonVisibilityInfo", "type": "int", "count": 4, "values": [ 128, 8, 0, 0 ] },
              { "name": "PhotonViewInverse", "type": "matrix4x4", "count": 16, "values": [ 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0 ] }
            ]""";
    /** The names of the uniforms it declares. */
    public static final List<String> UNIFORMS = uniformEntries().asList().stream()
            .map(entry -> entry.getAsJsonObject().get("name").getAsString()).toList();

    @Configurable(name = "TextureMaterial.litParticles.intensity")
    @ConfigNumber(range = {0, 16})
    public float intensity = 1f;

    public void copyFrom(LitParticles other) {
        setEnable(other.isEnable());
        intensity = other.intensity;
    }

    public void apply(ShaderInstance shader, MaterialContext context) {
        bind(shader, context, isEnable() ? intensity : 0f);
    }

    /**
     * Binds this frame's lights to a program that includes {@link #INCLUDE}, or turns its lighting off: at
     * intensity 0, in a preview, or with no lights. ⚠️ Every draw, either way: one program serves every
     * material of a kind. Off nulls the samplers, the one value {@code ShaderInstance.apply} leaves unbound.
     */
    public static void bind(ShaderInstance shader, MaterialContext context, float intensity) {
        if (intensity > 0 && !context.isRenderingPreview() && DynamicLightRenderer.hasLights()) {
            DynamicLightRenderer.bindLights(shader);
            shader.safeGetUniform("LitParticleParams").set(1f, intensity, 0f, 0f);
            return;
        }
        for (var sampler : SAMPLERS) {
            //noinspection DataFlowIssue
            shader.setSampler(sampler, null);
        }
        shader.safeGetUniform("LitParticleParams").set(0f, 0f, 0f, 0f);
    }

    /** Whether generated GLSL pulls the lights in. */
    public static boolean includedBy(String glsl) {
        return glsl.contains("#moj_import <" + INCLUDE + ">");
    }

    /** A shader json with the include's samplers and uniforms added. */
    public static String declareIn(String manifest) {
        var json = JsonParser.parseString(manifest).getAsJsonObject();
        var samplers = json.getAsJsonArray("samplers");
        for (var name : SAMPLERS) {
            var sampler = new JsonObject();
            sampler.addProperty("name", name);
            samplers.add(sampler);
        }
        json.getAsJsonArray("uniforms").addAll(uniformEntries());
        return json.toString();
    }

    /** Whether {@code name} is a sampler or uniform the include declares. */
    public static boolean declares(String name) {
        return SAMPLERS.contains(name) || UNIFORMS.contains(name);
    }

    /** The uniforms' json entries, as {@code hdr_particle.json} has them. */
    public static JsonArray uniformEntries() {
        return JsonParser.parseString(UNIFORMS_JSON).getAsJsonArray();
    }
}
