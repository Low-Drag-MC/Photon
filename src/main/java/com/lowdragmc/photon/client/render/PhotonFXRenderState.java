package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The extracted per-frame render state of {@link PhotonParticleGroup}: camera-relative geometry
 * baked per (RenderType) batch. {@link #submit} pushes each batch as custom geometry — the
 * {@code SubmitNodeStorage} passed to particle-group submission implements
 * {@link SubmitNodeCollector}, so no extra event hook is needed; solid/translucent routing comes
 * from the RenderType and the actual draw happens in {@code CustomFeatureRenderer} through the
 * shared buffer source (identical RenderTypes merge into one draw across emitters).
 */
public final class PhotonFXRenderState implements ParticleGroupRenderState {

    record Batch(RenderType renderType, BakingVertexConsumer geometry) {
    }

    private final List<Batch> batches = new ArrayList<>();
    /** Recycled consumers — extraction runs every frame; keeps the op/payload arrays warm. */
    private final ArrayDeque<BakingVertexConsumer> pool = new ArrayDeque<>();
    /** Identity pose: geometry is baked camera-relative, matching the submit phase's space. */
    private final PoseStack identityPose = new PoseStack();

    /** A pooled consumer to bake one batch's vertices into; pair with {@link #endBatch}, which
     *  attaches the RenderType and drops empty batches. */
    public BakingVertexConsumer beginBatch() {
        var consumer = pool.poll();
        if (consumer == null) {
            consumer = new BakingVertexConsumer();
        }
        return consumer;
    }

    public void endBatch(RenderType renderType, BakingVertexConsumer geometry) {
        if (geometry.isEmpty()) {
            pool.offer(geometry);
            return;
        }
        batches.add(new Batch(renderType, geometry));
    }

    public boolean isEmpty() {
        return batches.isEmpty();
    }

    @Override
    public void submit(SubmitNodeCollector collector, CameraRenderState camera) {
        collector.submitParticleGroup();
        for (var batch : batches) {
            collector.submitCustomGeometry(identityPose, batch.renderType(),
                    (pose, buffer) -> batch.geometry().replay(buffer));
        }
    }

    /** Frame boundary (vanilla calls it after the dispatchers drained the storage). */
    @Override
    public void clear() {
        for (var batch : batches) {
            batch.geometry().reset();
            pool.offer(batch.geometry());
        }
        batches.clear();
    }
}
