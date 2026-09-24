package com.lowdragmc.photon.client.render;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.neoforged.neoforge.client.submit.RenderPhaseKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * The extracted state of {@link PhotonParticleGroup}: the bake tasks of the visible emitters, submitted to
 * {@link PhotonFeatureRenderer} with the view's {@link PhotonViewSettings}.
 */
public final class PhotonFXRenderState implements ParticleGroupRenderState {

    private final List<PhotonBakeTask> tasks = new ArrayList<>();

    public void defer(PhotonBakeTask task) {
        tasks.add(task);
    }

    @Override
    public void submit(SubmitNodeCollector collector, CameraRenderState camera) {
        var settings = PhotonViewSettings.current();
        // still submit while the post-effect chain has work, even with no FX
        var stack = settings.effects() ? settings.postEffects() : null;
        if (tasks.isEmpty() && (stack == null || !stack.wantsExecution())) {
            return;
        }
        // vanilla clears this state after the dispatchers drained the storage
        var frame = new PhotonWorldRenderState.Frame(List.copyOf(tasks), settings);
        collector.submitSpecial(RenderPhaseKeys.SOLID,
                new PhotonSubmit(frame, PhotonStage.AFTER_OPAQUE_FEATURES));
        collector.submitSpecial(RenderPhaseKeys.AFTER_TERRAIN,
                new PhotonSubmit(frame, PhotonStage.AFTER_TRANSLUCENT_PARTICLES));
    }

    @Override
    public void clear() {
        tasks.clear();
    }
}
