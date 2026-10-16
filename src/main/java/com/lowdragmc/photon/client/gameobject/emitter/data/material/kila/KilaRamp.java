package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BiConsumer;

/** Gradient map: looks a channel of the colour up in an editable gradient, baked into a 256x1 texture. */
@OnlyIn(Dist.CLIENT)
public class KilaRamp extends KilaModule {
    /** ⚠️ ordinals MIRRORED IN {@code kila_ramp}. */
    public enum Input {
        LUMINANCE, RED, ALPHA, U, V
    }

    public enum Mode {
        REPLACE, MULTIPLY, REPLACE_ALPHA
    }

    @Persisted(subPersisted = true)
    public final GradientColor gradient = new GradientColor(0xFF2A0000, 0xFFB01800, 0xFFFF7A10, 0xFFFFE070, 0xFFFFFFFF);
    @Persisted
    public float intensity = 1;
    @Persisted
    public Input input = Input.LUMINANCE;
    @Persisted
    public Mode mode = Mode.REPLACE;
    @Persisted(subPersisted = true)
    public final KilaDriver offset = new KilaDriver(0);

    private final KilaBakedGradient baked = new KilaBakedGradient(gradient);

    @Override
    public String id() {
        return "ramp";
    }

    @Override
    public Category category() {
        return Category.COLOR;
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_RAMP").vec4("KilaRamp", 1, 0, 0, 0).ivec4("KilaRampOpts")
                .dynamicTexture("RAMP", baked::textureId).params(KilaParam.RAMP_OFFSET);
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        shader.safeGetUniform("KilaRamp").set(intensity, 0f, 0f, 0f);
        shader.safeGetUniform("KilaRampOpts").set(input.ordinal(), mode.ordinal(), 0, 0);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(KilaParam.RAMP_OFFSET, offset);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.gradient(group, "kila.ramp.gradient", gradient::copy, v -> KilaBakedGradient.copy(v, gradient));
        KilaUI.number(group, "kila.ramp.intensity", () -> intensity, v -> intensity = v, 1, 0, 64);
        KilaUI.choice(group, "kila.ramp.input", Input.values(), () -> input, v -> input = v);
        KilaUI.choice(group, "kila.ramp.mode", Mode.values(), () -> mode, v -> mode = v);
        KilaUI.driver(group, "kila.ramp.offset", offset, -1, 1);
    }
}
