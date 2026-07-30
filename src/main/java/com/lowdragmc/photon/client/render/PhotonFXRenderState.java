package com.lowdragmc.photon.client.render;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;

import java.util.ArrayList;
import java.util.List;

/**
 * The extracted per-frame render state of {@link PhotonParticleGroup}: not geometry, but the list of
 * {@link IPhotonFXCollector.BakeTask}s the visible emitters registered — vanilla's extract/prepare
 * split, where extraction only decides WHICH emitters draw and the vertices are generated in the
 * drain.
 * <p>
 * {@link #submit} hands the tasks to whichever collector the engine is submitting into. That
 * collector IS the view identity: the world submits into {@code LevelRenderer}'s
 * {@code SubmitNodeStorage}, every LDLib2 scene into the one it built itself, so an emitter never
 * has to ask which view it is drawing for. Photon then opens its own render passes at the stages the
 * jobs ask for ({@link PhotonStage}) instead of handing anything to a vanilla feature renderer —
 * that is what keeps HDR/bloom and the custom pipelines under Photon's control.
 */
public final class PhotonFXRenderState implements ParticleGroupRenderState {

    private final List<IPhotonFXCollector.BakeTask> tasks = new ArrayList<>();

    /** Register one emitter's deferred geometry generation (called from extraction). */
    public void defer(IPhotonFXCollector.BakeTask task) {
        tasks.add(task);
    }

    @Override
    public void submit(SubmitNodeCollector collector, CameraRenderState camera) {
        if (tasks.isEmpty() || !(collector instanceof IPhotonFXCollector fx)) {
            return; // a collector without a Photon bucket (e.g. Iris' shadow pass) simply skips fx
        }
        fx.photonFXTasks().addAll(tasks);
        PhotonWorldRenderState.trackCollector(fx);
    }

    /** Frame boundary (vanilla calls it after the dispatchers drained the storage). The collector
     *  owns the copy it took in {@link #submit}, so dropping ours here is safe. */
    @Override
    public void clear() {
        tasks.clear();
    }
}
