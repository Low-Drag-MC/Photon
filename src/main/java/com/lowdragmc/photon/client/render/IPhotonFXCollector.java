package com.lowdragmc.photon.client.render;

import java.util.List;

/**
 * The per-VIEW sink for Photon FX — Photon's counterpart to the vanilla submit-node buckets.
 * <p>
 * <b>Identity is the collector instance</b>, which the engine already threads for us: the world
 * submits into {@code LevelRenderer}'s {@code SubmitNodeStorage}, and every LDLib2 scene submits into
 * the one it constructed itself ({@code WorldSceneRenderer.submitNodeStorage}). So an emitter never
 * asks which view it is drawing for — it registers work with whatever collector its render state was
 * submitted into, exactly like a vanilla particle group.
 * <p>
 * <b>Bake tasks, not baked jobs.</b> What is collected is deferred work: extraction only decides
 * WHICH emitters are visible, and the geometry is generated inside {@link #drain} once this view's
 * {@link PhotonViewSettings} are known — mirroring vanilla's {@code extract} (compact records) /
 * {@code prepare} (GPU buffers) split. A view that never drains therefore never pays for baking, and
 * one emitter submitted into two views bakes correctly for each.
 * <p>
 * <b>Getting one.</b> {@code SubmitNodeStorage} implements this via mixin, so the world and every
 * LDLib2 scene satisfy it for free. A third party either submits into a vanilla storage (nothing to
 * do) or implements this interface on its own collector.
 * <p>
 * <b>Lifetime is the implementor's.</b> Vanilla's {@code clearSubmitNodes()} knows nothing about this
 * bucket; {@link #drain} clears what it consumes, and the frame boundary drops the rest.
 */
public interface IPhotonFXCollector {

    /** Deferred geometry generation for one emitter group, run by {@link #drain}. */
    @FunctionalInterface
    interface BakeTask {
        void bake(PhotonViewSettings settings, List<PhotonWorldRenderState.DrawJob> out);
    }

    /** This view's collected tasks, in submission order. Mutable — the submit phase appends here. */
    List<BakeTask> photonFXTasks();

    /** Geometry baked from {@link #photonFXTasks()} on this view's first drain of the frame, held
     *  until every stage has taken its share. Mutable; the drain removes what it consumes. */
    List<PhotonWorldRenderState.DrawJob> photonFXBaked();

    /** This view's draw settings. The owner publishes them before its submit phase. */
    default PhotonViewSettings photonViewSettings() {
        return PhotonViewSettings.DEFAULT;
    }

    default void photonViewSettings(PhotonViewSettings settings) {
    }

    /**
     * Bake this view's collected work and draw the jobs belonging to {@code stage}. Call from
     * wherever this view's FX belong — the world does it on the matching {@code RenderLevelStageEvent}
     * sub-events, an LDLib2 scene from {@code PhotonParticleManager.afterRender}. Takes only what it
     * draws; the frame boundary drops the rest. Render thread only, outside any open render pass.
     */
    default void drain(PhotonStage stage) {
        PhotonWorldRenderState.drain(this, stage);
    }
}
