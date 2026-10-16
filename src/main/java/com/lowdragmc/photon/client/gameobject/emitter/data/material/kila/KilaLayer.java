package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/** A texture blended over the main one. Layer 2's uv offset is not drivable: no room left between the stages. */
@OnlyIn(Dist.CLIENT)
public class KilaLayer extends KilaModule {
    /** ⚠️ ordinals MIRRORED IN {@code kila_layer_blend}. */
    public enum Blend {
        MULTIPLY, ADD, LERP, SCREEN
    }

    private final int index;
    private final String prefix;
    private final String define;
    private final String slot;

    @Persisted(subPersisted = true)
    public final KilaTexture texture;
    @Persisted
    public Blend blend = Blend.MULTIPLY;
    @Persisted
    public boolean affectsColor = true;
    @Persisted
    public boolean affectsAlpha = true;
    @Persisted(subPersisted = true)
    public final KilaDriver strength = new KilaDriver(1);
    @Persisted(subPersisted = true)
    public final KilaDriver offsetU = new KilaDriver(0);
    @Persisted(subPersisted = true)
    public final KilaDriver offsetV = new KilaDriver(0);

    public KilaLayer() {
        this(1);
    }

    public KilaLayer(int index) {
        this.index = index;
        String suffix = index == 1 ? "" : String.valueOf(index);
        this.prefix = "KilaLayer" + suffix;
        this.define = "KILA_LAYER" + suffix;
        this.slot = "LAYER" + suffix;
        this.texture = index == 1 ? new KilaTexture(KilaTextures.NOISE_CLOUD).scroll(0.1f, 0.05f)
                : new KilaTexture(KilaTextures.NOISE_CELLS).scroll(-0.05f, 0.15f);
        if (index != 1) blend = Blend.ADD;
    }

    @Override
    public String id() {
        return index == 1 ? "layer" : "layer" + index;
    }

    @Override
    public Category category() {
        return Category.TEXTURE;
    }

    private boolean first() {
        return index == 1;
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define(define).fragmentUvChain(prefix).texture(slot, texture);
        if (first()) {
            layout.params(KilaParam.LAYER_STRENGTH, KilaParam.LAYER_OFFSET_U, KilaParam.LAYER_OFFSET_V);
        } else {
            layout.params(KilaParam.LAYER2_STRENGTH);
        }
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        texture.uploadUv(shader, prefix);
        shader.safeGetUniform(prefix + "Opts").set(texture.flags(), texture.colorMode.ordinal(), blend.ordinal(),
                (affectsColor ? 1 : 0) | (affectsAlpha ? 2 : 0));
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        out.accept(texture);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        if (first()) {
            out.accept(KilaParam.LAYER_STRENGTH, strength);
            out.accept(KilaParam.LAYER_OFFSET_U, offsetU);
            out.accept(KilaParam.LAYER_OFFSET_V, offsetV);
        } else {
            out.accept(KilaParam.LAYER2_STRENGTH, strength);
        }
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.texture(group, "kila.layer.texture", texture, KilaUI.TextureKind.COLOR, true);
        KilaUI.choice(group, "kila.layer.blend", Blend.values(), () -> blend, v -> blend = v);
        KilaUI.driver(group, "kila.layer.strength", strength, 0, 1);
        KilaUI.bool(group, "kila.layer.affects_color", () -> affectsColor, v -> affectsColor = v);
        KilaUI.bool(group, "kila.layer.affects_alpha", () -> affectsAlpha, v -> affectsAlpha = v);
        if (first()) {
            KilaUI.driver(group, "kila.layer.offset_u", offsetU, -16, 16);
            KilaUI.driver(group, "kila.layer.offset_v", offsetV, -16, 16);
        }
    }
}
