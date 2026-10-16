package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialContext;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector2f;

import java.util.function.BiConsumer;

/** Pushes the uv of the slots it targets by a noise; as a flow map it carries them along its vectors instead. */
@OnlyIn(Dist.CLIENT)
public class KilaDistortion extends KilaModule {
    /** ⚠️ bits MIRRORED IN {@code kila.fsh} (KILA_FLOW_*). */
    public enum Target {
        MAIN(1), LAYER(2), MASK1(4), MASK2(8), DISSOLVE(16);

        public final int bit;

        Target(int bit) {
            this.bit = bit;
        }
    }

    /** ⚠️ ordinals MIRRORED IN {@code kila_flow}. */
    public enum Mask {
        NONE, U, V, RADIAL
    }

    public enum Mode {
        OFFSET, FLOW_MAP
    }

    @Persisted(subPersisted = true)
    public final KilaTexture texture = new KilaTexture(KilaTextures.NOISE_FLOW).scroll(0.15f, 0.08f);
    @Persisted(subPersisted = true)
    public final KilaDriver strength = new KilaDriver(0.05f);
    @Persisted
    public Vector2f amount = new Vector2f(1, 1);
    /** remaps the texture to -1..1 so it pushes both ways */
    @Persisted
    public boolean bidirectional = true;
    @Persisted
    public Mask mask = Mask.NONE;
    @Persisted
    public Mode mode = Mode.OFFSET;
    /** flow map cycles per second */
    @Persisted
    public float flowSpeed = 0.5f;
    @Persisted
    public boolean main = true;
    @Persisted
    public boolean layer = true;
    @Persisted
    public boolean mask1;
    @Persisted
    public boolean mask2;
    @Persisted
    public boolean dissolve = true;

    @Override
    public String id() {
        return "distortion";
    }

    @Override
    public Category category() {
        return Category.TEXTURE;
    }

    public int targets() {
        return (main ? Target.MAIN.bit : 0) | (layer ? Target.LAYER.bit : 0) | (mask1 ? Target.MASK1.bit : 0)
                | (mask2 ? Target.MASK2.bit : 0) | (dissolve ? Target.DISSOLVE.bit : 0);
    }

    @Override
    public long structure() {
        return mode.ordinal();
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_DISTORT").fragmentUvChain("KilaDistort").vec4("KilaDistortParams", 1, 1, 1, 0)
                .source("DISTORT", "KilaDistort", texture, false).params(KilaParam.DISTORT_STRENGTH);
        if (mode == Mode.FLOW_MAP) layout.define("KILA_FLOWMAP").vec4("KilaFlowMap", 0.5f, 0, 0, 0);
    }

    @Override
    public void apply(ShaderInstance shader, MaterialContext context, float[] scratch) {
        texture.uploadUv(shader, "KilaDistort");
        if (texture.isProcedural()) texture.uploadSource(shader, "KilaDistort", scratch);
        shader.safeGetUniform("KilaDistortOpts").set(texture.flags(), 0, targets(), mask.ordinal());
        shader.safeGetUniform("KilaDistortParams").set(amount.x, amount.y, bidirectional ? 1f : 0f, 0f);
        if (mode == Mode.FLOW_MAP) shader.safeGetUniform("KilaFlowMap").set(flowSpeed, 0f, 0f, 0f);
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        out.accept(texture);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(KilaParam.DISTORT_STRENGTH, strength);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.choice(group, "kila.distortion.mode", Mode.values(), () -> mode, v -> mode = v);
        KilaUI.driver(group, "kila.distortion.strength", strength, -1, 1);
        KilaUI.showWhen(group, KilaUI.number(group, "kila.distortion.flow_speed", () -> flowSpeed, v -> flowSpeed = v,
                0.5f, -16, 16), () -> mode == Mode.FLOW_MAP);
        KilaUI.texture(group, "kila.distortion.texture", texture, KilaUI.TextureKind.VECTOR, true, true);
        KilaUI.vec2(group, "kila.distortion.amount", () -> amount, v -> amount = v);
        KilaUI.bool(group, "kila.distortion.bidirectional", () -> bidirectional, v -> bidirectional = v);
        KilaUI.choice(group, "kila.distortion.mask", Mask.values(), () -> mask, v -> mask = v);
        var targets = KilaUI.subGroup(group, "kila.distortion.targets", false);
        KilaUI.bool(targets, "kila.distortion.target.main", () -> main, v -> main = v);
        KilaUI.bool(targets, "kila.distortion.target.layer", () -> layer, v -> layer = v);
        KilaUI.bool(targets, "kila.distortion.target.mask1", () -> mask1, v -> mask1 = v);
        KilaUI.bool(targets, "kila.distortion.target.mask2", () -> mask2, v -> mask2 = v);
        KilaUI.bool(targets, "kila.distortion.target.dissolve", () -> dissolve, v -> dissolve = v);
    }
}
