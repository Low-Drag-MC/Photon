package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** A painted lit sphere looked up by the normal; a flat billboard needs {@link KilaShading} or reads one texel. */
@OnlyIn(Dist.CLIENT)
public class KilaMatcap extends KilaModule {
    /** ⚠️ ordinals MIRRORED IN {@code kila.fsh} (KilaMatcap.y). */
    public enum Blend {
        ADD, MULTIPLY, REPLACE
    }

    @Persisted(subPersisted = true)
    public final KilaTexture texture = new KilaTexture(KilaTextures.MATCAP_GLASS);
    @Persisted
    public Blend blend = Blend.ADD;
    @Persisted
    public float strength = 1;

    @Override
    public String id() {
        return "matcap";
    }

    @Override
    public Category category() {
        return Category.LIGHTING;
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_MATCAP").vec4("KilaMatcap", 1, 0, 0, 0).texture("MATCAP", texture);
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        shader.safeGetUniform("KilaMatcap").set(strength, (float) blend.ordinal(), 0f, 0f);
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        out.accept(texture);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        // the lookup reads rgb only: no colour mode row
        KilaUI.add(group, new KilaTextureField("kila.matcap.texture", texture, KilaUI.TextureKind.COLOR, false),
                "kila.matcap.texture");
        KilaUI.choice(group, "kila.matcap.blend", Blend.values(), () -> blend, v -> blend = v);
        KilaUI.number(group, "kila.matcap.strength", () -> strength, v -> strength = v, 1, 0, 16);
    }
}
