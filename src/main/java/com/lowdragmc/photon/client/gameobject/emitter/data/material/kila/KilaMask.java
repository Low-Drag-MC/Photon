package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;

import java.util.function.BiConsumer;

/** Multiplies alpha (optionally colour) by one channel of its own texture, on its own uv chain. */
public class KilaMask extends KilaModule {
    private final int index;
    private final String prefix;

    @Persisted(subPersisted = true)
    public final KilaTexture texture = new KilaTexture(KilaTextures.GRADIENT_RADIAL);
    @Persisted
    public float strength = 1;
    @Persisted
    public boolean invert;
    @Persisted
    public float power = 1;
    @Persisted
    public boolean affectsColor;
    @Persisted(subPersisted = true)
    public final KilaDriver offsetU = new KilaDriver(0);
    @Persisted(subPersisted = true)
    public final KilaDriver offsetV = new KilaDriver(0);

    public KilaMask(int index) {
        this.index = index;
        this.prefix = "KilaMask" + index;
        texture.wrap(KilaTexture.Wrap.CLAMP, KilaTexture.Wrap.CLAMP);
    }

    @Override
    public String id() {
        return "mask" + index;
    }

    public int index() {
        return index;
    }

    @Override
    public Category category() {
        return Category.ALPHA;
    }

    private KilaParam paramU() {
        return index == 1 ? KilaParam.MASK1_OFFSET_U : KilaParam.MASK2_OFFSET_U;
    }

    private KilaParam paramV() {
        return index == 1 ? KilaParam.MASK1_OFFSET_V : KilaParam.MASK2_OFFSET_V;
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        // <prefix>Params: x = strength, y = invert, z = power, w = also multiplies rgb
        layout.define("KILA_MASK" + index).uvChain(prefix).vec4(prefix + "Params", 1, 0, 1, 0)
                .source("MASK" + index, prefix, texture, true).params(paramU(), paramV());
    }

    @Override
    public void apply(KilaValues values, boolean preview, float[] scratch) {
        texture.uploadUv(values, prefix);
        values.set(prefix + "Opts", texture.flags(), 0, 0, 0);
        texture.uploadSource(values, prefix, scratch);
        values.set(prefix + "Params", strength, invert ? 1f : 0f, power, affectsColor ? 1f : 0f);
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        out.accept(texture);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(paramU(), offsetU);
        out.accept(paramV(), offsetV);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.texture(group, "kila.mask.texture", texture, KilaUI.TextureKind.SCALAR, true, true);
        KilaUI.number(group, "kila.mask.strength", () -> strength, v -> strength = v, 1, 0, 1);
        KilaUI.number(group, "kila.mask.power", () -> power, v -> power = v, 1, 0.01f, 16);
        KilaUI.bool(group, "kila.mask.invert", () -> invert, v -> invert = v);
        KilaUI.bool(group, "kila.mask.affects_color", () -> affectsColor, v -> affectsColor = v);
        KilaUI.driver(group, "kila.mask.offset_u", offsetU, -16, 16);
        KilaUI.driver(group, "kila.mask.offset_v", offsetV, -16, 16);
    }
}
