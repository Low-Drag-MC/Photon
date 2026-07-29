package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.world.phys.AABB;

/**
 * The particle-engine host of every Photon FX object (registered for
 * {@link PhotonParticleRenderTypes#FX} via {@code RegisterParticleGroupsEvent}, which fires for
 * every {@code ParticleEngine} construction — the editor's scene engines get one too).
 * <p>
 * Ticking is inherited; extraction drives the per-frame chain that used to hang off the 1.21
 * {@code Particle.render} call ({@link FXObject#extractFrame}: deltaTime bookkeeping, timeline
 * frame animation, {@code FXRuntime}'s onUpdateFrame) and bakes visible emitter geometry into a
 * {@link PhotonFXRenderState} (M1 decision D2: geometry is built HERE, not at draw time).
 */
public final class PhotonParticleGroup extends ParticleGroup<FXObject> {

    /** Reused across frames; vanilla resets it via {@link ParticleGroupRenderState#clear()}. */
    private final PhotonFXRenderState renderState = new PhotonFXRenderState();

    /** Rolling extract-time samples (ns) — the world-side "frame time" the editor stats used to
     *  read off the render pass; M4 reconnects the editor HUD to this. */
    private static final long[] EXTRACT_TIMES = new long[60];
    private static int extractIndex;

    public PhotonParticleGroup(ParticleEngine engine) {
        super(engine);
    }

    @Override
    public ParticleGroupRenderState extractRenderState(Frustum frustum, Camera camera, float partialTick) {
        var startTime = System.nanoTime();
        for (var fxObject : particles) {
            // every FX object gets its frame drive, visible or not — timelines must not freeze
            // when the emitter happens to be off-screen
            fxObject.extractFrame(partialTick);
        }
        for (var fxObject : particles) {
            if (!(fxObject instanceof Emitter emitter) || !emitter.isVisible()) {
                continue;
            }
            var cullBox = emitter.getCullBox(partialTick);
            if (cullBox != null && cullBox != AABB.INFINITE && !frustum.isVisible(cullBox)) {
                continue;
            }
            emitter.extractBatches(renderState, camera, partialTick);
        }
        EXTRACT_TIMES[extractIndex] = System.nanoTime() - startTime;
        extractIndex = (extractIndex + 1) % EXTRACT_TIMES.length;
        return renderState;
    }

    /** Average extract time in microseconds (the world-side frame-time stat). */
    public static long averageExtractTimeUs() {
        long sum = 0;
        for (long sample : EXTRACT_TIMES) {
            sum += sample;
        }
        return sum / EXTRACT_TIMES.length / 1000;
    }
}
