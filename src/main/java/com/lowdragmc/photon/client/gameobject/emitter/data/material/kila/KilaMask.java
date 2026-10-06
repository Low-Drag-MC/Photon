package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/** Multiplies alpha (optionally colour) by one channel of its own texture, on its own uv chain. */
@OnlyIn(Dist.CLIENT)
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
        layout.define("KILA_MASK" + index).fragmentUvChain(prefix).vec4(prefix + "Params", 1, 0, 1, 0)
                .source("MASK" + index, prefix, texture, true).params(paramU(), paramV());
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        texture.uploadUv(shader, prefix);
        shader.safeGetUniform(prefix + "Opts").set(texture.flags(), 0, 0, 0);
        texture.uploadSource(shader, prefix, scratch);
        shader.safeGetUniform(prefix + "Params").set(strength, invert ? 1f : 0f, power, affectsColor ? 1f : 0f);
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
