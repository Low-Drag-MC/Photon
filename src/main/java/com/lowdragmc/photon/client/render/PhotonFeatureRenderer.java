package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.client.compat.iris.IrisCompat;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.oit.OitStage;
import net.neoforged.neoforge.client.event.RegisterFeatureRenderersEvent;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Photon's feature renderer, registered with every dispatcher (the world's and each LDLib2 scene's). A group is only
 * prepared here (baked and uploaded, no pass open) for its view's seams to draw ({@link PhotonFrameSeams}); it executes
 * only in the world's OIT passes, which its OIT draws join ({@link PhotonOit}).
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
                var frame = submit.frame();
                // a frame nothing makes seams for is not drawn; don't spend the bake on it
                if (PhotonFrameSeams.claim(frame)) {
                    frame.prepare();
                }
            }
        });
    }

    @Override
    public void executeGroup(FeatureFrameContext context, @Nullable OitStage stage, RenderPass renderPass,
                             int groupIndex, List<PhotonSubmit> submits, boolean strictlyOrdered) {
        // everything else is drawn at the view's seams, where no pass is open
        if (stage == null || IrisCompat.isShadowPass()) {
            return;
        }
        for (var submit : submits) {
            if (submit.stage() == PhotonStage.LAST) {
                submit.frame().executeOit(stage, renderPass);
            }
        }
    }
}
