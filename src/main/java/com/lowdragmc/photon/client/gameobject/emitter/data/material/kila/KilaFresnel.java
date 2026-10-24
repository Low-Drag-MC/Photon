package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.configurator.ui.ConfiguratorGroup;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import org.joml.Vector3f;

import java.util.function.BiConsumer;

/** Rim light or rim fade from the angle to the view. A billboard always faces the camera, so this is for meshes. */
public class KilaFresnel extends KilaModule {
    /** ⚠️ ordinals MIRRORED IN {@code kila_fresnel}. */
    public enum Mode {
        RIM, FADE_EDGES, FADE_CENTER
    }

    @Persisted
    public Mode mode = Mode.RIM;
    @Persisted
    public float power = 2;
    @Persisted
    public float scale = 1;
    @Persisted
    public HDRColor color = new HDRColor(0.4f, 0.8f, 1f, 1f, 2f);
    /** added to the view direction, so the rim leans as if lit from one side */
    @Persisted
    public Vector3f offset = new Vector3f();
    @Persisted(subPersisted = true)
    public final KilaDriver strength = new KilaDriver(1);

    @Override
    public String id() {
        return "fresnel";
    }

    @Override
    public Category category() {
        return Category.LIGHTING;
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_FRESNEL")
                // x = power, y = scale, z = mode (0 rim glow, 1 fades the edges, 2 fades the centre)
                .vec4("KilaFresnel", 2, 1, 0, 0)
                .vec4("KilaFresnelColor")
                // xyz = offset added to the view direction, faking where the light comes from
                .vec4("KilaFresnelOffset")
                .params(KilaParam.FRESNEL_STRENGTH);
    }

    @Override
    public void apply(KilaValues values, boolean preview, float[] scratch) {
        values.set("KilaFresnel", power, scale, (float) mode.ordinal(), 0f);
        var rim = color.toVector4fOpaque();
        values.set("KilaFresnelColor", rim.x, rim.y, rim.z, 1f);
        values.set("KilaFresnelOffset", offset.x, offset.y, offset.z, 0f);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(KilaParam.FRESNEL_STRENGTH, strength);
    }

    @Override
    public void buildModuleConfigurator(ConfiguratorGroup group) {
        KilaUI.choice(group, "kila.fresnel.mode", Mode.values(), () -> mode, v -> mode = v);
        KilaUI.driver(group, "kila.fresnel.strength", strength, 0, 1);
        KilaUI.number(group, "kila.fresnel.power", () -> power, v -> power = v, 2, 0.01f, 16);
        KilaUI.number(group, "kila.fresnel.scale", () -> scale, v -> scale = v, 1, 0, 16);
        KilaUI.hdr(group, "kila.fresnel.color", () -> color, v -> color = v, false);
        KilaUI.vec3(group, "kila.fresnel.offset", () -> offset, v -> offset = v);
    }
}
