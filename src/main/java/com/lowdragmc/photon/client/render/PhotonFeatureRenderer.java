package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.neoforged.neoforge.client.event.RegisterFeatureRenderersEvent;

import java.util.List;

/**
 * Photon's feature renderer, registered with every dispatcher (the world's and each LDLib2 scene's). The opaque
 * stage executes in {@code SOLID}, the translucent one in {@code AFTER_TERRAIN} after vanilla's particles.
 */
public final class PhotonFeatureRenderer implements FeatureRenderer<PhotonSubmit> {

    public static final FeatureRendererType<PhotonSubmit> TYPE = FeatureRendererType.create("photon:fx");

    public static void register(RegisterFeatureRenderersEvent event) {
        event.register(TYPE, new PhotonFeatureRenderer());
    }

    @Override
    public void prepareGroup(FeatureFrameContext context, List<PhotonSubmit> submits, boolean strictlyOrdered) {
        if (IrisCompat.isShadowPass()) {
            return;
        }
        // custom pipelines compile during the bake; keep the pack from substituting them
        IrisCompat.runWithoutPackPrograms(() -> {
            for (var submit : submits) {
                submit.frame().prepare();
            }
        });
    }

    @Override
    public void executeGroup(FeatureFrameContext context, int groupIndex, List<PhotonSubmit> submits,
                             boolean strictlyOrdered) {
        if (IrisCompat.isShadowPass()) {
            return;
        }
        IrisCompat.runWithoutPackPrograms(() -> {
            for (var submit : submits) {
                submit.frame().execute(submit.stage());
            }
        });
    }
}
