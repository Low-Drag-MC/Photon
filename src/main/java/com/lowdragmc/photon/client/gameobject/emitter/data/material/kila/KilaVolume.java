package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Turns the flat quad into a ball of fog: alpha is the view ray's path through a sphere (Inigo Quilez). */
@OnlyIn(Dist.CLIENT)
public class KilaVolume extends KilaModule {
    /** the sphere's radius as a share of the quad's half width */
    @Persisted
    public float size = 1;
    /** above 1 the core thickens */
    @Persisted
    public float density = 1;
    /** above 1 the edge sharpens */
    @Persisted
    public float power = 1;
    /** cut by the scene behind it; costs the scene depth copy */
    @Persisted
    public boolean clip = true;

    @Override
    public String id() {
        return "volume";
    }

    @Override
    public Category category() {
        return Category.ALPHA;
    }

    @Override
    public long structure() {
        return clip ? 1 : 0;
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_VOLUME").vec4("KilaVolume", 1, 1, 1, 0);
        if (clip) {
            layout.define("KILA_VOLUME_CLIP").mat4("U_InverseProjectionMatrix").sampler(PhotonShaderCompiler.SCENE_DEPTH);
        }
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        boolean bound = clip && KilaDepth.bindSceneDepth(shader, context);
        shader.safeGetUniform("KilaVolume").set(Math.max(size, 0.01f), density, power, bound ? 1f : 0f);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.number(group, "kila.volume.size", () -> size, v -> size = v, 1, 0.01f, 4);
        KilaUI.number(group, "kila.volume.density", () -> density, v -> density = v, 1, 0, 16);
        KilaUI.number(group, "kila.volume.power", () -> power, v -> power = v, 1, 0.05f, 16);
        KilaUI.bool(group, "kila.volume.clip", () -> clip, v -> clip = v);
    }
}
