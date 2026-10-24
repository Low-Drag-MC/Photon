package com.lowdragmc.photon.test.gametest;

import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import com.lowdragmc.photon.client.render.PhotonStage;
import com.lowdragmc.photon.client.render.PremultipliedBlendPlan;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Whether a late-composited renderer really goes into the late layer is decided by its materials' blends as
 * authored. The premultiplied variant of a blend is what it becomes once it is in the layer: an opaque or multiply
 * blend premultiplies into one that is safe there, so a decision read off it would depend on whether the group
 * baked before this one left the bake accumulating.
 */
public final class BlendStageGameTest {

    private static final String STAGE_IGNORES_THE_BAKE_BEFORE = "fx_stage_ignores_the_bake_before";

    private BlendStageGameTest() {}

    public static void registerFunctions() {
        PhotonGameTests.registerFunction(STAGE_IGNORES_THE_BAKE_BEFORE, helper -> {
            var accumulating = PremultipliedBlendPlan.accumulating();
            try {
                for (var preset : BlendMode.Preset.values()) {
                    // reading the destination or replacing it has no equivalent in a transparent layer
                    var expected = preset == BlendMode.Preset.OPAQUE || preset == BlendMode.Preset.MULTIPLY
                            ? RendererSetting.Layer.Translucent.stage : PhotonStage.DEFERRED;
                    for (var before : new boolean[]{false, true}) {
                        PremultipliedBlendPlan.setAccumulating(before);
                        var stage = late(preset).effectiveStage();
                        if (stage != expected) {
                            helper.fail(Component.literal("a late " + preset + " renderer baked after "
                                    + (before ? "an accumulating" : "an in-place") + " group goes to " + stage
                                    + ", not " + expected));
                            return;
                        }
                    }
                }
            } finally {
                PremultipliedBlendPlan.setAccumulating(accumulating);
            }
            helper.succeed();
        });
    }

    private static RendererSetting.Runtime late(BlendMode.Preset preset) {
        var emitter = new ParticleEmitter();
        var renderer = emitter.config.renderer;
        renderer.setLayer(RendererSetting.Layer.Translucent);
        renderer.setCompositeMode(FXCompositeMode.LATE);
        var material = new MaterialSetting(new TextureMaterial());
        material.setBlend(preset.create());
        renderer.getMaterials().clear();
        renderer.getMaterials().add(material);
        return emitter.config.defaultRenderRuntime;
    }

    public static void register(RegisterGameTestsEvent event,
                                Holder<TestEnvironmentDefinition<?>> environment) {
        PhotonGameTests.registerFunctionTest(event, STAGE_IGNORES_THE_BAKE_BEFORE, PhotonGameTests.defaultTestData(environment));
    }
}
